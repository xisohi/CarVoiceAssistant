package com.xisohi.car.voiceassistant.core

import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import kotlin.math.sqrt

/**
 * 语音识别封装（Vosk，完全离线）。
 *
 * 关键改进：
 * 1. 不依赖 Vosk 内置的端点检测（太敏感，说话中间间隙就误判），
 *    改为外部基于音频能量（RMS）自主判断静音端点。
 * 2. Model 预加载缓存：服务启动时调用 preload() 加载模型到内存，
 *    后续 create() 直接复用缓存的 Model，只创建 Recognizer（毫秒级），
 *    避免车机上每次识别都要等 2-3 秒模型加载。
 *
 * 用法：
 * 1. 服务启动时调用 SpeechRecognizer.preload(modelDir) 预加载
 * 2. 循环调用 feed() 获取 partial 识别结果
 * 3. 外部自己计算 RMS 判断是否静音，连续静音超阈值则结束
 * 4. 调用 finish() 获取最终识别文本
 */
class SpeechRecognizer private constructor(
    private var model: Model?,  // 改成 var，release() 后置空避免泄漏
    private val recognizer: Recognizer,
    private val isModelCached: Boolean = false
) {

    companion object {
        const val SAMPLE_RATE = 16000f

        // Model 缓存（预加载后复用，避免每次识别都重新加载模型）
        @Volatile
        private var cachedModel: Model? = null
        @Volatile
        private var cachedModelDir: String? = null

        /**
         * 预加载 Vosk 模型到内存（服务启动时调用，非阻塞后台执行）
         * 后续 create() 会直接复用缓存的 Model，只创建 Recognizer
         */
        fun preload(modelDir: File) {
            val dirPath = modelDir.absolutePath
            // 如果已经缓存了同一个模型，直接返回
            if (cachedModel != null && cachedModelDir == dirPath) {
                return
            }
            synchronized(this) {
                // 双重检查
                if (cachedModel != null && cachedModelDir == dirPath) {
                    return
                }
                // 释放旧的缓存模型
                cachedModel?.close()
                // 加载新模型并缓存
                cachedModel = Model(dirPath)
                cachedModelDir = dirPath
            }
        }

        /**
         * 创建语音识别器
         * 如果模型已预加载缓存，直接复用 Model，只创建 Recognizer（毫秒级）
         * 否则创建新的 Model（耗时，车机上可能 2-3 秒）
         */
        fun create(modelDir: File, grammar: List<String>? = null): SpeechRecognizer {
            val dirPath = modelDir.absolutePath
            val model: Model
            val isCached: Boolean

            // 尝试使用缓存的 Model
            val cached = cachedModel
            if (cached != null && cachedModelDir == dirPath) {
                model = cached
                isCached = true
            } else {
                // 没有缓存，创建新 Model（同时缓存起来供后续使用）
                model = Model(dirPath)
                isCached = false
                // 异步缓存这个 Model（不阻塞当前识别）
                synchronized(this) {
                    if (cachedModel == null || cachedModelDir != dirPath) {
                        cachedModel?.close()
                        cachedModel = model
                        cachedModelDir = dirPath
                    }
                }
            }

            val rec = if (grammar.isNullOrEmpty()) {
                Recognizer(model, SAMPLE_RATE)
            } else {
                // Vosk grammar 是 JSON 数组字符串，如 ["你好", "世界"]
                val grammarJson = grammar.joinToString(prefix = "[", postfix = "]") {
                    "\"${it.replace("\"", "\\\"")}\""
                }
                Recognizer(model, SAMPLE_RATE, grammarJson)
            }
            return SpeechRecognizer(model, rec, isCached)
        }

        /**
         * 释放缓存的 Model（Service 销毁时调用）
         * 调用后 cachedModel 置空，下次 create() 会重新加载
         * 小模型（40MB）占内存约 120MB，问题不大；
         * 但如果以后换大模型（1.3GB），会占约 1.5GB 内存，必须及时释放
         */
        fun releaseCachedModel() {
            synchronized(this) {
                try { cachedModel?.close() } catch (_: Exception) {}
                cachedModel = null
                cachedModelDir = null
            }
        }

        /**
         * 计算 16-bit PCM 音频的 RMS（均方根）能量值
         * 用于判断是否静音：值越小越安静
         */
        fun calculateRms(audioData: ShortArray, length: Int): Float {
            if (length <= 0) return 0f
            var sum = 0.0
            for (i in 0 until length) {
                val sample = audioData[i].toInt()
                sum += (sample * sample).toDouble()
            }
            val rms = sqrt(sum / length)
            return rms.toFloat()
        }
    }

    /**
     * 喂入 16kHz 单声道 PCM16 数据；返回部分识别文本（可能为 null）
     * 注意：不依赖 Vosk 的 acceptWaveForm 返回值做端点判断，端点由外部基于 RMS 自主判断
     */
    fun feed(data: ByteArray, len: Int): String? {
        // 调用 acceptWaveForm 让 Vosk 处理音频，但忽略返回值（不用于端点判断）
        recognizer.acceptWaveForm(data, len)
        // 总是返回 partial 结果
        val partial = textOf(recognizer.partialResult)
        return partial.ifEmpty { null }
    }

    /**
     * 结束识别并返回最终文本
     */
    fun finish(): String {
        return textOf(recognizer.finalResult)
    }

    fun release() {
        try { recognizer.close() } catch (_: Exception) {}
        // 注意：不要关闭 Model！
        // 如果 Model 是缓存的（isModelCached=true），关闭会导致后续识别失败
        // 如果 Model 不是缓存的，create() 中已经把它加入缓存了，也不要关闭
        // Model 的生命周期由缓存管理，应用退出时由系统回收
        // 但是要把本实例对 Model 的引用置空，避免 SpeechRecognizer 实例被意外长期持有时连带 Model 一起泄漏
        model = null
    }

    private fun textOf(json: String): String =
        try {
            JSONObject(json).optString("text", "")
        } catch (_: Exception) {
            ""
        }
}
