package com.xisohi.car.voiceassistant.core

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import com.xisohi.car.voiceassistant.download.ModelManager
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer

/**
 * 唤醒词引擎（Vosk 自由识别 + 关键词匹配）。
 *
 * 不用 grammar 限定模式：vosk-model-small-cn 是基于字的模型，
 * grammar 要求词必须在词表中，"小爱同学"这类词组会被忽略
 * （日志会报 "Ignoring word missing in vocabulary"）。
 *
 * 改为自由识别，在 partialResult / result 中检查是否包含唤醒词。
 * 唤醒后由服务层停止本引擎、释放 Recognizer，再开识别用的
 * Recognizer，保证同时只有一个 Vosk Recognizer 在跑，内存可控。
 *
 * 模型：与 ASR 共用同一个 Vosk 中文模型（vosk-model-small-cn-0.22）。
 * 唤醒词：当前硬编码为 ["小爱同学"]，后续可改为从配置文件读取。
 */
class WakeWordEngine(private val context: Context) {

    fun interface Callback {
        fun onWakeWord(keyword: String)
    }

    companion object {
        /** 唤醒词列表（可扩展为从配置文件读取） */
        val WAKE_WORDS = listOf("小爱同学")
        private const val SAMPLE_RATE = 16000
        private const val CHUNK_SIZE = 512
    }

    private var model: Model? = null
    private var recognizer: Recognizer? = null
    private var recordThread: Thread? = null

    @Volatile
    private var running = false

    /**
     * 启动唤醒监听。返回 false 表示模型未就绪或初始化失败。
     */
    @Synchronized
    fun start(callback: Callback): Boolean {
        stop()
        val modelDir = ModelManager.findAsrModelDir(context) ?: return false
        return try {
            model = Model(modelDir.absolutePath)
            // 自由识别（不用 grammar），small-cn 基于字，能输出"小爱同学"
            recognizer = Recognizer(model, SAMPLE_RATE.toFloat())

            running = true
            recordThread = Thread { recordLoop(callback) }.apply {
                name = "vosk-wake-record"
                start()
            }
            true
        } catch (e: Exception) {
            stop()
            false
        }
    }

    private fun recordLoop(callback: Callback) {
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuf <= 0) return
        val record = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuf * 2, SAMPLE_RATE)
        )
        record.startRecording()
        val buf = ShortArray(CHUNK_SIZE)
        try {
            while (running) {
                val n = record.read(buf, 0, buf.size)
                if (n <= 0) continue
                val rec = recognizer ?: continue
                // acceptWaveForm 返回 true 表示检测到静音端点，可取最终结果
                val endpoint = rec.acceptWaveForm(buf, n)
                val text = if (endpoint) {
                    parseText(rec.result)
                } else {
                    parseText(rec.partialResult)
                }
                if (text.isNotEmpty()) {
                    Log.d("WakeWord", "识别文本: '$text' (endpoint=$endpoint)")
                }
                // 去掉空格后再匹配（Vosk 可能在词之间加空格，如"小爱 同学"）
                val normalized = text.replace(" ", "")
                if (normalized.isNotEmpty() && WAKE_WORDS.any { normalized.contains(it) }) {
                    Log.i("WakeWord", "命中唤醒词: $text")
                    callback.onWakeWord(text)
                    // 命中后重置识别状态，继续监听下一次唤醒
                    rec.reset()
                    continue
                }
                if (endpoint) {
                    // 一句说完但没命中唤醒词，重置开始下一句
                    rec.reset()
                }
            }
        } finally {
            try {
                record.stop()
            } catch (_: Exception) {
            }
            record.release()
        }
    }

    /** 从 Vosk result JSON 中提取 text 字段 */
    private fun parseText(json: String): String {
        return try {
            JSONObject(json).optString("text", "").trim()
        } catch (_: Exception) {
            ""
        }
    }

    @Synchronized
    fun stop() {
        running = false
        try {
            recordThread?.join(1000)
        } catch (_: Exception) {
        }
        recordThread = null
        try {
            recognizer?.close()
        } catch (_: Exception) {
        }
        try {
            model?.close()
        } catch (_: Exception) {
        }
        recognizer = null
        model = null
    }
}
