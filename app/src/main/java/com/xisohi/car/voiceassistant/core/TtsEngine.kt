package com.xisohi.car.voiceassistant.core

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.xisohi.car.voiceassistant.BuildConfig
import java.util.Locale
import java.util.UUID

class TtsEngine(private val context: Context) : TextToSpeech.OnInitListener {

    interface Listener {
        fun onSpeakStart()
        fun onSpeakDone()
    }

    var listener: Listener? = null

    // 常用 TTS 引擎列表（按推荐优先级排序）
    private val fallbackEngines = listOf(
        "com.google.android.tts",       // Google TTS
        "com.svox.pico",                // Pico TTS（Android 原生）
        "com.huawei.hwvoicetts",        // 华为 TTS
        "com.samsung.SMT",              // 三星 TTS
        "com.xiaomi.tts",               // 小米 TTS
        "com.iflytek.speechcloud",      // 讯飞语记
        "com.iflytek.tts",              // 讯飞 TTS
        "com.baidu.tts",                // 百度 TTS
        "com.tencent.speech.tts"        // 腾讯 TTS
    )

    private var tts: TextToSpeech? = null
    @Volatile
    private var ready = false
    private val appContext = context.applicationContext
    private var currentEngineIndex = -1

    init {
        val preferred = BuildConfig.PREFERRED_TTS_ENGINE.ifBlank { null }
        if (preferred != null) {
            currentEngineIndex = -1
            initEngine(preferred)
        } else {
            currentEngineIndex = -2
            initEngine(null)
        }
    }

    private fun initEngine(engine: String?) {
        Log.d("TtsEngine", "尝试 TTS: ${engine ?: "系统默认"}")
        tts?.shutdown()
        tts = if (engine != null) {
            TextToSpeech(appContext, this, engine)
        } else {
            TextToSpeech(appContext, this)
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val currentTts = tts
            if (currentTts != null) {
                val result = currentTts.setLanguage(Locale.CHINESE)
                if (result != TextToSpeech.LANG_MISSING_DATA &&
                    result != TextToSpeech.LANG_NOT_SUPPORTED
                ) {
                    ready = true
                    setupListener(currentTts)
                    Log.d("TtsEngine", "TTS 就绪 ✅")
                    return
                } else {
                    Log.w("TtsEngine", "当前引擎不支持中文，切换...")
                    currentTts.shutdown()
                    tts = null
                    ready = false
                    tryNextEngine()
                    return
                }
            }
        } else {
            Log.e("TtsEngine", "TTS 初始化失败 (status=$status)")
            tts?.shutdown()
            tts = null
            ready = false
            tryNextEngine()
        }
    }

    private fun tryNextEngine() {
        when (currentEngineIndex) {
            -2 -> currentEngineIndex = 0
            -1 -> currentEngineIndex = 0
            else -> currentEngineIndex++
        }

        if (currentEngineIndex < fallbackEngines.size) {
            val engine = fallbackEngines[currentEngineIndex]
            Log.d("TtsEngine", "尝试备用引擎: $engine")
            initEngine(engine)
        } else {
            Log.e("TtsEngine", "所有引擎均不可用 ❌")
            ready = false
        }
    }

    private fun setupListener(tts: TextToSpeech) {
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
        if (!ready) {
            listener?.onSpeakDone()
            return
        }
        val utteranceId = UUID.randomUUID().toString()
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
    }

    fun stopSpeaking() {
        tts?.stop()
        listener?.onSpeakDone()
    }

    fun shutdown() {
        try {
            tts?.stop()
            tts?.shutdown()
        } catch (_: Exception) {
        }
        tts = null
        ready = false
    }
}