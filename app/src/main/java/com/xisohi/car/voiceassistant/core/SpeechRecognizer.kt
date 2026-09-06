package com.xisohi.car.voiceassistant.core

import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File

/**
 * 语音识别封装（Vosk，完全离线）。
 *
 * 关键用法：可传入 grammar（JSGF 语法词表），把识别域锁死在车控指令
 * 句式上——自由听写下 small 模型的准确率撑不起车控，限定语法后
 * 准确率与速度都明显提升。grammar 由 [IntentParser] 从意图模板生成。
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
                Recognizer(model, SAMPLE_RATE, grammar)
            }
            return SpeechRecognizer(model, rec)
        }
    }

    /** 喂入 16kHz 单声道 PCM16 数据；返回部分识别文本（可能为 null） */
    fun feed(data: ByteArray, len: Int): String? {
        return if (recognizer.acceptWaveForm(data, len)) {
            textOf(recognizer.result)
        } else {
            textOf(recognizer.partialResult).ifEmpty { null }
        }
    }

    /** 是否检测到语音结束（静音端点） */
    fun isEndpoint(): Boolean = recognizer.isEndpoint

    /** 结束识别并返回最终文本 */
    fun finish(): String {
        recognizer.setEndOfStream()
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
