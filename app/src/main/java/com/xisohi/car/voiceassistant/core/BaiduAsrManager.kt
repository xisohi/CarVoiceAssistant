package com.xisohi.car.voiceassistant.core

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.baidu.speech.EventListener
import com.baidu.speech.EventManager
import com.baidu.aipe.asr.AipeEventManagerFactory
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 配置验证结果（三态：成功/鉴权错误/网络错误）
 * 网络错误不应该被标记为"配置错误"，因为用户的 Key 可能是对的，只是当前网络不通
 */
sealed class VerifyResult {
    object Success : VerifyResult()
    data class AuthError(val message: String) : VerifyResult()   // Key 错误
    data class NetworkError(val message: String) : VerifyResult() // 网络问题
}

/**
 * 百度语音识别 SDK 封装
 *
 * 使用 AipeEventManagerFactory + 动态鉴权方式，
 * 在 create() 时通过 setAkSk() 传入鉴权信息（从设置页读取），
 * 同时在 ASR_START 参数中再传一次作为双保险。
 * 不依赖 AndroidManifest.xml 的 meta-data，实现"谁用谁填自己的 Key"。
 *
 * 采用"外部音频文件识别"模式（infile 参数）：
 * - 我们自己录音（保留端点检测、RMS显示、动态静音等逻辑）
 * - 录音结束后保存为临时 PCM 文件
 * - 调用百度 SDK 识别该文件
 *
 * 鉴权方式说明：
 * - 使用 AipeEventManagerFactory.create() 创建识别管理器
 * - 通过 setAkSk() 传入用户填的 Key
 * - 同时在 ASR_START 参数中再传一次作为双保险
 *
 * 注意：3.5.0+ 版本 SDK 必须使用 language 参数代替 pid 参数，
 *       否则会报 -3004 "App name unknown" 错误。
 */
class BaiduAsrManager private constructor(private val context: Context) {

    companion object {
        private const val TAG = "BaiduAsrManager"
        private const val PREFS_NAME = "baidu_asr_config"
        private const val KEY_APP_ID = "app_id"
        private const val KEY_API_KEY = "api_key"
        private const val KEY_SECRET_KEY = "secret_key"
        private const val KEY_CONFIG_STATUS = "config_status"  // 配置验证状态：ok/error/untested
        // 配置验证状态三态
        const val CONFIG_STATUS_OK = "ok"          // 测试连接通过，配置正确
        const val CONFIG_STATUS_ERROR = "error"    // 测试连接失败，配置错误
        const val CONFIG_STATUS_UNTESTED = "untested"  // 未测试或修改了配置，需要重新测试
        private const val RECOGNIZE_TIMEOUT_MS = 20000L  // 识别超时 20 秒

        @Volatile
        private var instance: BaiduAsrManager? = null

        /**
         * 获取单例实例（线程安全）
         * 使用 applicationContext 避免内存泄漏
         */
        fun getInstance(context: Context): BaiduAsrManager {
            return instance ?: synchronized(this) {
                instance ?: BaiduAsrManager(context.applicationContext).also { instance = it }
            }
        }
    }

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private var asrManager: EventManager? = null
    // AipeEventManagerFactory 必须提升为成员变量，不能是 init() 里的局部变量！
    // 原因：factory 是局部变量时，init() 返回后就被 GC 了。
    // 百度 SDK 里 EventManager 内部可能持有 factory 的引用，也可能不持有——
    // 这是个未定义的依赖关系，模拟器上运气好不崩，车机上可能偶发 NullPointerException 或鉴权失败。
    private var factory: AipeEventManagerFactory? = null
    private var isInitialized = false
    private var recognitionCallback: ((String?) -> Unit)? = null
    private var isRecognizing = false
    private var lastFinalResult: String? = null  // 保存 asr.partial 中的最终结果
    private val handler = Handler(Looper.getMainLooper())

    // 百度检测到说话结束的回调（asr.end 事件）
    // 用于流式识别：百度自己判 VAD 结束，通知上层停止录音
    private var onSpeechEndListener: (() -> Unit)? = null

    /**
     * 设置说话结束监听器（百度检测到 asr.end 时调用）
     * 流式识别模式下，百度内置 DNN VAD 会自动检测说话结束，
     * 上层收到此回调后应停止录音，等待最终识别结果。
     */
    fun setOnSpeechEndListener(listener: (() -> Unit)?) {
        onSpeechEndListener = listener
    }

    // 超时机制
    private val timeoutRunnable = Runnable {
        if (isRecognizing) {
            Log.w(TAG, "百度语音识别超时（${RECOGNIZE_TIMEOUT_MS}ms），自动取消")
            try {
                asrManager?.send("asr.cancel", null, null, 0, 0)
            } catch (e: Exception) {
                Log.e(TAG, "超时取消失败: ${e.message}")
            }
            isRecognizing = false
            val cb = recognitionCallback
            recognitionCallback = null
            cb?.invoke(null)
        }
    }

    // ==================== 配置管理 ====================

    fun saveConfig(appId: String, apiKey: String, secretKey: String) {
        prefs.edit()
            .putString(KEY_APP_ID, appId.trim())
            .putString(KEY_API_KEY, apiKey.trim())
            .putString(KEY_SECRET_KEY, secretKey.trim())
            .putString(KEY_CONFIG_STATUS, CONFIG_STATUS_UNTESTED)  // 配置修改后重置为"未测试"，需要重新测试连接
            .apply()
        Log.i(TAG, "百度语音配置已保存（验证状态重置为未测试，需重新测试连接）")
        // 释放旧引擎，强制下次使用时重新 init() 读取新配置
        releaseEngine()
        // 配置完整时立即用新配置初始化，确保修改后立即生效
        // （避免用户改了配置但没重启应用，导致还是用旧配置）
        if (appId.isNotEmpty() && apiKey.isNotEmpty() && secretKey.isNotEmpty()) {
            try {
                val success = init()
                if (success) {
                    Log.i(TAG, "百度语音配置已保存并立即初始化成功（新配置生效）")
                } else {
                    Log.w(TAG, "百度语音配置已保存，但立即初始化失败（下次使用时会重试）")
                }
            } catch (e: Exception) {
                Log.w(TAG, "百度语音配置已保存，但立即初始化异常: ${e.message}（下次使用时会重试）")
            }
        }
    }

    fun getAppId(): String = prefs.getString(KEY_APP_ID, "") ?: ""
    fun getApiKey(): String = prefs.getString(KEY_API_KEY, "") ?: ""
    fun getSecretKey(): String = prefs.getString(KEY_SECRET_KEY, "") ?: ""

    fun isConfigured(): Boolean {
        return getAppId().isNotEmpty() && getApiKey().isNotEmpty() && getSecretKey().isNotEmpty()
    }

    /**
     * 设置配置验证状态（测试连接后调用）
     * @param status CONFIG_STATUS_OK / CONFIG_STATUS_ERROR / CONFIG_STATUS_UNTESTED
     */
    fun setConfigStatus(status: String) {
        prefs.edit().putString(KEY_CONFIG_STATUS, status).apply()
        Log.i(TAG, "配置验证状态已设置为: $status")
    }

    /**
     * 获取配置验证状态
     * @return CONFIG_STATUS_OK / CONFIG_STATUS_ERROR / CONFIG_STATUS_UNTESTED
     */
    fun getConfigStatus(): String {
        return prefs.getString(KEY_CONFIG_STATUS, CONFIG_STATUS_UNTESTED) ?: CONFIG_STATUS_UNTESTED
    }

    /**
     * 配置是否验证通过（兼容旧代码，等价于 getConfigStatus() == CONFIG_STATUS_OK）
     */
    fun isConfigVerified(): Boolean {
        return getConfigStatus() == CONFIG_STATUS_OK
    }

    /**
     * 验证百度语音配置是否正确（调用 token 接口）
     *
     * 原理：调用百度 OAuth token 接口，如果返回 access_token 说明 Key 正确；
     * 如果返回 error 说明 Key 错误；如果超时说明网络不通。
     *
     * @param apiKey API Key
     * @param secretKey Secret Key
     * @return VerifyResult 三态：Success / AuthError（Key错） / NetworkError（网络问题）
     */
    fun verifyConfig(apiKey: String, secretKey: String): VerifyResult {
        return try {
            val url = "https://aip.baidubce.com/oauth/2.0/token?grant_type=client_credentials" +
                    "&client_id=$apiKey&client_secret=$secretKey"
            val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            val responseCode = conn.responseCode
            if (responseCode == 200) {
                val response = conn.inputStream.bufferedReader().use { it.readText() }
                val json = org.json.JSONObject(response)
                if (json.has("access_token")) {
                    val token = json.getString("access_token")
                    Log.i(TAG, "配置验证成功，token=${token.take(10)}...")
                    VerifyResult.Success
                } else {
                    val error = json.optString("error", "未知错误")
                    val errorDesc = json.optString("error_description", "")
                    Log.w(TAG, "配置验证失败（鉴权错误）: $error - $errorDesc")
                    VerifyResult.AuthError("验证失败: $error - $errorDesc")
                }
            } else {
                Log.w(TAG, "配置验证 HTTP 错误: $responseCode")
                VerifyResult.AuthError("HTTP 错误: $responseCode")
            }
        } catch (e: java.net.SocketTimeoutException) {
            Log.w(TAG, "配置验证超时（网络不通）")
            VerifyResult.NetworkError("网络超时，请检查网络连接")
        } catch (e: java.net.UnknownHostException) {
            Log.w(TAG, "配置验证失败: 无法解析主机名（网络不通）")
            VerifyResult.NetworkError("无法连接服务器，请检查网络")
        } catch (e: Exception) {
            Log.w(TAG, "配置验证异常: ${e.message}")
            VerifyResult.NetworkError("网络错误: ${e.message}")
        }
    }

    /**
     * 检测网络是否真的通（调用百度 token 接口，只检测连通性，不验证 Key）
     *
     * 车机场景：WiFi 已连接但可能无外网（如手机热点没开流量、路由器断网）。
     * isNetworkAvailable() 只能检测网络是否连接，不能检测是否能访问外网。
     *
     * @return true=网络通，false=网络不通
     */
    fun isNetworkReachable(): Boolean {
        // ★ 用中性探测目标，避免用无效凭证触发限流/连接重置
        // 多个 URL 依次尝试，只要有一个能通就说明网络通
        val probeUrls = listOf(
            "https://www.baidu.com",        // 百度主站
            "https://aip.baidubce.com",     // 百度 AI 主站（不带参数）
            "https://www.qq.com"             // 备用
        )
        for (url in probeUrls) {
            try {
                val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                conn.requestMethod = "HEAD"  // HEAD 比 GET 更轻量，只拿响应头
                conn.connectTimeout = 3000
                conn.readTimeout = 3000
                conn.instanceFollowRedirects = false  // 不跟随重定向，3xx 也算通
                val code = conn.responseCode
                conn.disconnect()
                // 2xx/3xx/4xx 都说明网络通（4xx 是业务错误，不是网络问题）
                // 只有 5xx 或连不上才说明网络有问题
                if (code in 200..499) {
                    Log.d(TAG, "网络连通性检测: $url → HTTP $code（网络通）")
                    return true
                }
            } catch (e: Exception) {
                Log.d(TAG, "网络探测 $url 失败: ${e.javaClass.simpleName} - ${e.message}")
            }
        }
        // 所有 URL 都探测失败，说明网络不通
        Log.w(TAG, "网络连通性检测: 所有探测 URL 均失败（网络不通）")
        return false
    }

    fun clearConfig() {
        prefs.edit().clear().apply()
        release()
        Log.i(TAG, "百度语音配置已清除")
    }

    // ==================== SDK 初始化（AipeEventManagerFactory：动态传入用户 Key，不依赖 meta-data） ====================

    fun init(): Boolean {
        if (isInitialized) return true
        if (!isConfigured()) {
            Log.w(TAG, "百度语音未配置")
            return false
        }

        return try {
            val appId = getAppId()
            val apiKey = getApiKey()
            val secretKey = getSecretKey()

            // 打印 Key 的前几位和后几位（不打印完整 Key）
            Log.d(TAG, "鉴权信息: appId=$appId, " +
                    "apiKey=${apiKey.take(4)}...${apiKey.takeLast(4)} (len=${apiKey.length}), " +
                    "secretKey=${secretKey.take(4)}...${secretKey.takeLast(4)} (len=${secretKey.length})")

            // 用 AipeEventManagerFactory，动态传用户填的 Key
            // 不再依赖 AndroidManifest.xml 的 meta-data，实现"谁用谁填自己的 Key"
            // 注意：factory 必须赋值给成员变量，不能是局部变量！
            // 否则 init() 返回后 factory 被 GC，可能导致 EventManager 内部引用失效。
            factory = AipeEventManagerFactory().apply {
                setAkSk(appId, apiKey, secretKey)
            }
            asrManager = factory?.create(context, "asr")
            asrManager?.registerListener(eventListener)
            isInitialized = true

            Log.i(TAG, "百度语音 SDK 初始化成功（AipeEventManagerFactory，用户填的 Key, appId=$appId）")
            printSignatureMd5(context)
            true
        } catch (e: Exception) {
            Log.e(TAG, "百度语音 SDK 初始化失败: ${e.message}", e)
            false
        }
    }

    private fun printSignatureMd5(context: Context) {
        try {
            val packageInfo = context.packageManager.getPackageInfo(
                context.packageName,
                android.content.pm.PackageManager.GET_SIGNATURES
            )
            val signatures = packageInfo.signatures
            if (signatures != null && signatures.isNotEmpty()) {
                val signature = signatures[0]
                val md = java.security.MessageDigest.getInstance("MD5")
                md.update(signature.toByteArray())
                val digest = md.digest()
                val md5 = digest.joinToString(":") { "%02X".format(it) }
                Log.i(TAG, "应用签名 MD5: $md5")
                Log.i(TAG, "应用包名: ${context.packageName}")
                Log.i(TAG, "请确认百度开放平台上填写的签名 MD5 和包名是否一致")
            }
        } catch (e: Exception) {
            Log.e(TAG, "获取应用签名失败: ${e.message}", e)
        }
    }

    /**
     * 只释放语音识别引擎（EventManager + factory），保留配置
     *
     * 用于服务销毁时调用（VoiceAssistantService.onDestroy）：
     * - 释放 EventManager，避免服务反复启停（START_STICKY）时 SDK 内部资源累积泄漏
     * - 保留 appId/apiKey/secretKey 配置（已在成员变量中，下次 init() 直接复用）
     * - isInitialized 设为 false，下次使用时需要重新 init()（但不需要重新读取配置）
     *
     * 注意：这不会销毁单例本身，单例一直存在，只是释放了 SDK 引擎资源。
     */
    fun releaseEngine() {
        try {
            handler.removeCallbacks(timeoutRunnable)
            asrManager?.unregisterListener(eventListener)
            asrManager = null
            factory = null  // 释放 factory 引用（注意：不要调用 factory.close()，百度SDK没有这个方法）
            isInitialized = false
            isRecognizing = false
            recognitionCallback = null
            Log.d(TAG, "百度语音识别引擎已释放（配置保留，下次 init() 复用）")
        } catch (e: Exception) {
            Log.e(TAG, "释放百度语音识别引擎失败: ${e.message}", e)
        }
    }

    /**
     * 完全释放（兼容旧代码，内部调用 releaseEngine()）
     *
     * @deprecated 请使用 releaseEngine()，语义更清晰
     */
    @Deprecated("Use releaseEngine() instead", ReplaceWith("releaseEngine()"))
    fun release() {
        releaseEngine()
    }

    // ==================== 语音识别 ====================

    fun recognizeFile(audioFile: File, callback: (String?) -> Unit) {
        if (!audioFile.exists()) {
            Log.e(TAG, "音频文件不存在: ${audioFile.absolutePath}")
            callback(null)
            return
        }
        if (!isInitialized) {
            if (!init()) {
                callback(null)
                return
            }
        }
        if (isRecognizing) {
            Log.w(TAG, "正在识别中，忽略重复调用")
            return
        }

        recognitionCallback = callback
        isRecognizing = true

        try {
            val appId = getAppId()
            val apiKey = getApiKey()
            val secretKey = getSecretKey()

            // 识别参数
            // ASR_START 参数中再传一次鉴权信息（双保险，主鉴权在 AipeEventManagerFactory.setAkSk）
            val params = JSONObject().apply {
                // 鉴权信息（动态覆盖 meta-data）
                put("appid", appId)
                put("appkey", apiKey)
                put("secretkey", secretKey)

                // 基础参数
                put("accept-audio-data", false)
                put("accept-audio-volume", true)
                put("disable-punctuation", false)

                // 识别模型：3.5.0+ SDK 使用 language 代替 pid
                // put("pid", 1537)  // 旧版参数，3.5.0+ 已废弃
                put("language", "cmn-Hans-CN")  // 中文普通话

                // 音频格式
                put("format", "pcm")
                put("rate", 16000)
                put("channel", 1)

                // 外部音频文件（infile 模式）
                put("infile", audioFile.absolutePath)
                put("outfile", "")

                // VAD 设置
                put("vad", "dnn")
            }

            Log.i(TAG, "识别参数: ${params.toString()}")

            // 重置最终结果
            lastFinalResult = null
            asrManager?.send("asr.start", params.toString(), null, 0, 0)
            Log.i(TAG, "百度语音识别已启动，文件: ${audioFile.name}, 大小: ${audioFile.length()} bytes")

            // 启动超时机制
            handler.removeCallbacks(timeoutRunnable)
            handler.postDelayed(timeoutRunnable, RECOGNIZE_TIMEOUT_MS)
        } catch (e: Exception) {
            Log.e(TAG, "启动百度语音识别失败: ${e.message}", e)
            isRecognizing = false
            recognitionCallback = null
            callback(null)
        }
    }

    /**
     * 启动流式语音识别（边录边识别 + 百度内置 DNN VAD）
     *
     * 和 recognizeFile() 的区别：
     * - recognizeFile()：先录完整段音频保存为文件，再一次性上传识别
     * - startStreamingRecognition()：边录边传，百度 SDK 实时识别，内置 DNN VAD 自动检测说话开始/结束
     *
     * 使用流程：
     * 1. 调用本方法启动识别（内部会重置 BaiduAudioStream）
     * 2. 录音线程调用 BaiduAudioStream.getInstance().write() 实时写入音频
     * 3. 录音结束调用 BaiduAudioStream.getInstance().closeStream() 通知百度 SDK
     * 4. 百度 SDK 识别完成后通过 callback 返回结果
     *
     * @param callback 识别结果回调（识别完成时调用，参数为识别文本，失败为 null）
     */
    fun startStreamingRecognition(callback: (String?) -> Unit) {
        if (!isInitialized) {
            if (!init()) {
                callback(null)
                return
            }
        }
        if (isRecognizing) {
            Log.w(TAG, "正在识别中，忽略重复调用")
            return
        }

        recognitionCallback = callback
        isRecognizing = true

        try {
            val appId = getAppId()
            val apiKey = getApiKey()
            val secretKey = getSecretKey()

            // 识别参数
            val params = JSONObject().apply {
                // 鉴权信息（动态覆盖 meta-data）
                put("appid", appId)
                put("appkey", apiKey)
                put("secretkey", secretKey)

                // 基础参数
                // 注意：accept-audio-data = true 表示百度不自己录音，由外部通过 asr.audio 事件喂音频
                // 这是流式识别的关键参数！必须设为 true，否则百度会自己开麦克风录音，忽略我们喂的音频
                // accept-audio-data = false：百度自己开麦录音（和官方 Demo 一致）
                // 注意：无论 true 还是 false，百度都会自己开麦！true 只是额外接受外部 asr.audio 数据。
                // 我们的方案是：唤醒阶段我们占麦，识别阶段释放麦克风让百度自己录，避免麦克风冲突。
                put("accept-audio-data", false)
                put("accept-audio-volume", false)
                put("disable-punctuation", false)

                // 识别模型：3.5.0+ SDK 使用 language 代替 pid
                put("language", "cmn-Hans-CN")  // 中文普通话

                // 音频格式
                put("format", "pcm")
                put("rate", 16000)
                put("channel", 1)

                // 注意：流式识别不写 infile 参数！
                // infile 只接受本地文件路径，用于文件识别模式。
                // 流式识别通过 accept-audio-data = true + asr.audio 事件实时喂音频。

                // VAD 设置：使用百度内置 DNN VAD，自动检测说话开始/结束
                put("vad", "dnn")
            }

            Log.i(TAG, "流式识别参数: ${params.toString()}")

            // 重置最终结果
            lastFinalResult = null
            asrManager?.send("asr.start", params.toString(), null, 0, 0)
            Log.i(TAG, "百度流式语音识别已启动（边录边识别 + DNN VAD）")

            // 启动超时机制
            handler.removeCallbacks(timeoutRunnable)
            handler.postDelayed(timeoutRunnable, RECOGNIZE_TIMEOUT_MS)
        } catch (e: Exception) {
            Log.e(TAG, "启动百度流式语音识别失败: ${e.message}", e)
            isRecognizing = false
            recognitionCallback = null
            callback(null)
        }
    }

    /**
     * 发送音频数据给百度 SDK（录音线程每读一块音频调用一次）
     *
     * 这是流式识别的核心：实时把音频数据通过 asr.audio 事件发给百度 SDK。
     * 注意：必须在 startStreamingRecognition() 之后调用，且音频格式必须是
     * 16kHz、单声道、16bit 小端序 PCM。
     *
     * @param data 音频数据（PCM16 小端序）
     * @param offset 起始偏移（默认 0）
     * @param length 有效数据长度（默认 data.size）
     */
    fun sendAudioData(data: ByteArray, offset: Int = 0, length: Int = data.size) {
        if (!isRecognizing) return
        try {
            asrManager?.send("asr.audio", null, data, offset, length)
        } catch (e: Exception) {
            Log.e(TAG, "发送音频数据失败: ${e.message}", e)
        }
    }

    /**
     * 通知百度 SDK 音频输入结束（录音结束时调用）
     *
     * 发送 asr.stop 事件，百度 SDK 收到后会停止接收音频，开始返回最终识别结果。
     * 注意：这不会取消识别，只是告诉百度 SDK "没有更多音频了"。
     */
    fun stopRecognition() {
        if (!isRecognizing) return
        try {
            asrManager?.send("asr.stop", null, null, 0, 0)
            Log.i(TAG, "已发送 asr.stop，通知百度 SDK 音频输入结束")
        } catch (e: Exception) {
            Log.e(TAG, "发送 asr.stop 失败: ${e.message}", e)
        }
    }

    fun cancel() {
        if (!isRecognizing) return
        handler.removeCallbacks(timeoutRunnable)
        try {
            asrManager?.send("asr.cancel", null, null, 0, 0)
            isRecognizing = false
            recognitionCallback = null
            Log.i(TAG, "百度语音识别已取消")
        } catch (e: Exception) {
            Log.e(TAG, "取消百度语音识别失败: ${e.message}", e)
        }
    }

    // ==================== 事件处理 ====================

    private val eventListener = object : EventListener {
        override fun onEvent(name: String?, params: String?, data: ByteArray?, offset: Int, length: Int) {
            Log.d(TAG, "百度语音事件: $name")
            if (!params.isNullOrEmpty()) {
                Log.d(TAG, "  params: $params")
            }

            when (name) {
                "asr.ready" -> {
                    Log.i(TAG, "SDK 就绪")
                }
                "asr.begin" -> {
                    Log.i(TAG, "检测到说话开始")
                }
                "asr.end" -> {
                    Log.i(TAG, "百度检测到说话结束（DNN VAD）")
                    // 通知上层：百度已经判句结束，可以停止录音了
                    onSpeechEndListener?.invoke()
                }
                "asr.partial" -> {
                    // 临时识别结果
                    val result = parseRecogResult(params)
                    if (result != null) {
                        val results = result.first
                        val isFinal = result.second
                        if (results.isNotEmpty()) {
                            Log.i(TAG, "识别结果: ${results[0]} (final=$isFinal)")
                            if (isFinal) {
                                lastFinalResult = results[0]
                                Log.i(TAG, "保存最终识别结果: $lastFinalResult")
                            }
                        }
                    }
                }
                "asr.finish" -> {
                    // 识别结束（可能成功或失败）
                    handler.removeCallbacks(timeoutRunnable)
                    val result = parseRecogResult(params)
                    if (result != null) {
                        val hasError = result.third
                        val errorCode = result.fourth
                        val subErrorCode = result.fifth
                        val desc = result.sixth
                        val results = result.first

                        if (hasError) {
                            Log.e(TAG, "识别失败: error=$errorCode, sub_error=$subErrorCode, desc=$desc")
                            isRecognizing = false
                            val cb = recognitionCallback
                            recognitionCallback = null
                            cb?.invoke(null)
                        } else {
                            var finalText = if (results.isNotEmpty()) results[0] else ""
                            // 如果 asr.finish 中没有结果，使用 asr.partial 中保存的最终结果
                            if (finalText.isEmpty() && lastFinalResult != null) {
                                finalText = lastFinalResult!!
                                Log.i(TAG, "使用 asr.partial 中保存的最终结果: $finalText")
                            }
                            Log.i(TAG, "识别成功: $finalText")
                            isRecognizing = false
                            val cb = recognitionCallback
                            recognitionCallback = null
                            cb?.invoke(finalText)
                        }
                    } else {
                        // 如果无法解析结果，但有保存的最终结果，也认为成功
                        if (lastFinalResult != null) {
                            Log.i(TAG, "asr.finish 无法解析，但使用保存的最终结果: $lastFinalResult")
                            isRecognizing = false
                            val cb = recognitionCallback
                            recognitionCallback = null
                            cb?.invoke(lastFinalResult)
                        } else {
                            Log.w(TAG, "无法解析识别结果")
                            isRecognizing = false
                            val cb = recognitionCallback
                            recognitionCallback = null
                            cb?.invoke(null)
                        }
                    }
                    // 重置最终结果
                    lastFinalResult = null
                }
                "asr.exit" -> {
                    Log.i(TAG, "识别引擎退出")
                }
                "asr.volume" -> {
                    // 音量回调，忽略
                }
                else -> {
                    Log.d(TAG, "其他事件: $name")
                }
            }
        }
    }

    // ==================== 结果解析 ====================

    /**
     * 解析识别结果
     * @return Tuple6? (results, isFinal, hasError, errorCode, subErrorCode, desc)
     */
    private fun parseRecogResult(jsonStr: String?):
            Tuple6<Array<String>, Boolean, Boolean, Int, Int, String>? {
        if (jsonStr.isNullOrEmpty()) return null

        return try {
            val json = JSONObject(jsonStr)
            val error = json.optInt("error", -1)
            val subError = json.optInt("sub_error", -1)
            val desc = json.optString("desc", "")
            val resultType = json.optString("result_type", "")

            val hasError = error != 0
            val isFinal = "final_result" == resultType

            var results = emptyArray<String>()
            if (!hasError) {
                val arr = json.optJSONArray("results_recognition")
                if (arr != null) {
                    results = Array(arr.length()) { arr.getString(it) }
                }
            }

            Tuple6(results, isFinal, hasError, error, subError, desc)
        } catch (e: Exception) {
            Log.e(TAG, "解析识别结果失败: ${e.message}", e)
            null
        }
    }

    // 简单的六元组
    private data class Tuple6<out A, out B, out C, out D, out E, out F>(
        val first: A,
        val second: B,
        val third: C,
        val fourth: D,
        val fifth: E,
        val sixth: F
    )
}
