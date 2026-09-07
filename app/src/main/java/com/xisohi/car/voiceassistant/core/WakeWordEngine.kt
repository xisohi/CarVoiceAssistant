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
 * 唤醒词从 SharedPreferences 读取，支持自定义。
 * 默认为 "小爱同学"。
 */
class WakeWordEngine(private val context: Context) {

    fun interface Callback {
        fun onWakeWord(keyword: String)
    }

    companion object {
        private const val SAMPLE_RATE = 16000
        private const val CHUNK_SIZE = 512
        private const val PREFS_NAME = "voice_assistant_prefs"
        private const val KEY_WAKE_WORD = "wake_word"
    }

    private var model: Model? = null
    private var recognizer: Recognizer? = null
    private var recordThread: Thread? = null

    @Volatile
    private var running = false
    private var wakeWords: List<String> = emptyList()

    /**
     * 启动唤醒监听。返回 false 表示模型未就绪或初始化失败。
     */
    @Synchronized
    fun start(callback: Callback): Boolean {
        stop()
        val modelDir = ModelManager.findAsrModelDir(context) ?: return false

        // 读取自定义唤醒词
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val custom = prefs.getString(KEY_WAKE_WORD, "小爱同学") ?: "小爱同学"
        wakeWords = listOf(custom.trim())
        Log.d("WakeWord", "唤醒词: $wakeWords")

        return try {
            model = Model(modelDir.absolutePath)
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
                val endpoint = rec.acceptWaveForm(buf, n)
                val text = if (endpoint) {
                    parseText(rec.result)
                } else {
                    parseText(rec.partialResult)
                }
                if (text.isNotEmpty()) {
                    Log.d("WakeWord", "识别文本: '$text' (endpoint=$endpoint)")
                }
                val normalized = text.replace(" ", "")
                if (normalized.isNotEmpty() && wakeWords.any { normalized.contains(it) }) {
                    Log.i("WakeWord", "命中唤醒词: $text")
                    callback.onWakeWord(text)
                    rec.reset()
                    continue
                }
                if (endpoint) {
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