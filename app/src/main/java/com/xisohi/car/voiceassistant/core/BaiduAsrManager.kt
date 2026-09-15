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
class BaiduAsrManager(private val context: Context) {

    companion object {
        private const val TAG = "BaiduAsrManager"
        private const val PREFS_NAME = "baidu_asr_config"
        private const val KEY_APP_ID = "app_id"
        private const val KEY_API_KEY = "api_key"
        private const val KEY_SECRET_KEY = "secret_key"
        private const val RECOGNIZE_TIMEOUT_MS = 20000L  // 识别超时 20 秒
    }

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private var asrManager: EventManager? = null
    private var isInitialized = false
    private var recognitionCallback: ((String?) -> Unit)? = null
    private var isRecognizing = false
    private var lastFinalResult: String? = null  // 保存 asr.partial 中的最终结果
    private val handler = Handler(Looper.getMainLooper())

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
            .apply()
        Log.i(TAG, "百度语音配置已保存")
        release()
    }

    fun getAppId(): String = prefs.getString(KEY_APP_ID, "") ?: ""
    fun getApiKey(): String = prefs.getString(KEY_API_KEY, "") ?: ""
    fun getSecretKey(): String = prefs.getString(KEY_SECRET_KEY, "") ?: ""

    fun isConfigured(): Boolean {
        return getAppId().isNotEmpty() && getApiKey().isNotEmpty() && getSecretKey().isNotEmpty()
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
            val factory = AipeEventManagerFactory()
            factory.setAkSk(appId, apiKey, secretKey)
            asrManager = factory.create(context, "asr")
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

    fun release() {
        try {
            handler.removeCallbacks(timeoutRunnable)
            asrManager?.unregisterListener(eventListener)
            asrManager = null
            isInitialized = false
            isRecognizing = false
            recognitionCallback = null
            Log.d(TAG, "百度语音 SDK 已释放")
        } catch (e: Exception) {
            Log.e(TAG, "释放百度语音 SDK 失败: ${e.message}", e)
        }
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
                    Log.i(TAG, "检测到说话结束")
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
