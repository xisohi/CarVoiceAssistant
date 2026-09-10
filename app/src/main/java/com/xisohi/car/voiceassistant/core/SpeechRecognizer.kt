package com.xisohi.car.voiceassistant.core

import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import kotlin.math.sqrt

/**
 * 语音识别封装（Vosk，完全离线）。
 *
 * 关键改进：不依赖 Vosk 内置的端点检测（太敏感，说话中间间隙就误判），
 * 改为外部基于音频能量（RMS）自主判断静音端点。
 *
 * 用法：
 * 1. 循环调用 feed() 获取 partial 识别结果
 * 2. 外部自己计算 RMS 判断是否静音，连续静音超阈值则结束
 * 3. 调用 finish() 获取最终识别文本
 */
class SpeechRecognizer private constructor(
    private val model: Model,
    private val recognizer: Recognizer
) {

    companion object {
        const val SAMPLE_RATE = 16000f

        fun create(modelDir: File, grammar: List<String>? = null): SpeechRecognizer {
            val model = Model(modelDir.absolutePath)
            val rec = if (grammar.isNullOrEmpty()) {
                Recognizer(model, SAMPLE_RATE)
            } else {
                // Vosk grammar 是 JSON 数组字符串，如 ["你好", "世界"]
                val grammarJson = grammar.joinToString(prefix = "[", postfix = "]") {
                    "\"${it.replace("\"", "\\\"")}\""
                }
                Recognizer(model, SAMPLE_RATE, grammarJson)
            }
            return SpeechRecognizer(model, rec)
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
        try { model.close() } catch (_: Exception) {}
    }

    private fun textOf(json: String): String =
        try {
            JSONObject(json).optString("text", "")
        } catch (_: Exception) {
            ""
        }
}
