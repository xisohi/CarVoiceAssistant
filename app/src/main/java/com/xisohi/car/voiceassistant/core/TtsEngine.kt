package com.xisohi.car.voiceassistant.core

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.xisohi.car.voiceassistant.BuildConfig
import java.util.Locale
import java.util.UUID

/**
 * 离线语音合成封装。
 *
 * 默认使用系统离线 TTS 引擎（多数车机预装 Pico，中文离线可用）；
 * 如需更自然的音色，可在 PREFERRED_TTS_ENGINE 指定其他离线引擎
 * （如 sherpa-onnx 自建引擎）或换用 sherpa-onnx VITS 接入点。
 */
class TtsEngine(context: Context) : TextToSpeech.OnInitListener {

    interface Listener {
        /** 开始播报（用于"播报期间不响应唤醒"防回声误触发） */
        fun onSpeakStart()
        /** 播报结束（恢复唤醒监听） */
        fun onSpeakDone()
    }

    var listener: Listener? = null

    private val engine: String? = BuildConfig.PREFERRED_TTS_ENGINE.ifBlank { null }
    private val tts: TextToSpeech =
        if (engine != null) TextToSpeech(context.applicationContext, this, engine)
        else TextToSpeech(context.applicationContext, this)

    @Volatile
    private var ready = false

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) {
            ready = false
            return
        }
        val result = tts.setLanguage(Locale.CHINESE)
        ready = result != TextToSpeech.LANG_MISSING_DATA &&
                result != TextToSpeech.LANG_NOT_SUPPORTED
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                listener?.onSpeakStart()
            }

            override fun onDone(utteranceId: String?) {
                listener?.onSpeakDone()
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                listener?.onSpeakDone()
            }
        })
    }

    val isReady: Boolean get() = ready

    fun speak(text: String) {
        if (!ready) return
        val utteranceId = UUID.randomUUID().toString()
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
    }

    /** 打断当前播报（用户再次唤醒时） */
    fun stopSpeaking() {
        tts.stop()
        listener?.onSpeakDone()
    }

    fun shutdown() {
        try {
            tts.stop()
            tts.shutdown()
        } catch (_: Exception) {
        }
    }
}
