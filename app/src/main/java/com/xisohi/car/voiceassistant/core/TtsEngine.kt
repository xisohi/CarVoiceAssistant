package com.xisohi.car.voiceassistant.core

import android.content.Context
import android.media.AudioManager
import android.os.Bundle
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
        "com.iflytek.vflynote",         // 讯飞语记（用户已安装）
        "com.iflytek.speechcloud",      // 讯飞语音+
        "com.iflytek.tts",              // 讯飞 TTS
        "com.google.android.tts",       // Google TTS
        "com.svox.pico",                // Pico TTS（Android 原生）
        "com.huawei.hwvoicetts",        // 华为 TTS
        "com.samsung.SMT",              // 三星 TTS
        "com.xiaomi.tts",               // 小米 TTS
        "com.baidu.tts",                // 百度 TTS
        "com.tencent.speech.tts"        // 腾讯 TTS
    )

    private var tts: TextToSpeech? = null
    @Volatile
    private var ready = false
    private val appContext = context.applicationContext
    private var currentEngineIndex = -1

    // TTS 播报时的音量控制：临时调高系统媒体音量，播报完成后恢复
    private var originalMediaVolume: Int = -1
    private var isVolumeBoosted = false

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
                    // 设置语速和音调，让播报更快更清晰
                    // 语速 1.5x：车机场景需要快速响应，播报更干脆
                    // 音调 1.1x：稍微调高一点，声音更清晰
                    currentTts.setSpeechRate(1.5f)
                    currentTts.setPitch(1.1f)
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
                // TTS 播报完成，恢复系统媒体音量
                restoreMediaVolume()
                listener?.onSpeakDone()
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                // 出错时也恢复音量
                restoreMediaVolume()
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
        // 临时调高系统媒体音量（提高到最大音量的 80%），让 TTS 播报更响亮
        boostMediaVolume()
        // 设置 TTS 自身音量为最大（1.0）
        val params = Bundle()
        params.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f)
        params.putFloat(TextToSpeech.Engine.KEY_PARAM_PAN, 0.0f)  // 左右声道平衡
        val utteranceId = UUID.randomUUID().toString()
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, params, utteranceId)
    }

    /**
     * 临时调高系统媒体音量到最大音量的 80%，让 TTS 播报更响亮
     * 播报完成后在 onSpeakDone 中恢复
     */
    private fun boostMediaVolume() {
        try {
            val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val currentVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
            val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            // 目标音量：最大音量的 80%，但不低于当前音量
            val targetVolume = maxOf(currentVolume, (maxVolume * 0.8f).toInt())
            if (targetVolume > currentVolume && !isVolumeBoosted) {
                originalMediaVolume = currentVolume
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, targetVolume, 0)
                isVolumeBoosted = true
                Log.d("TtsEngine", "TTS播报临时提高媒体音量: $currentVolume -> $targetVolume (最大: $maxVolume)")
            }
        } catch (e: Exception) {
            Log.w("TtsEngine", "提高媒体音量失败: ${e.message}")
        }
    }

    /**
     * 恢复 TTS 播报前的系统媒体音量
     */
    private fun restoreMediaVolume() {
        try {
            if (isVolumeBoosted && originalMediaVolume >= 0) {
                val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, originalMediaVolume, 0)
                Log.d("TtsEngine", "TTS播报完成，恢复媒体音量: $originalMediaVolume")
                isVolumeBoosted = false
                originalMediaVolume = -1
            }
        } catch (e: Exception) {
            Log.w("TtsEngine", "恢复媒体音量失败: ${e.message}")
        }
    }

    fun stopSpeaking() {
        tts?.stop()
        restoreMediaVolume()
        listener?.onSpeakDone()
    }

    fun shutdown() {
        try {
            tts?.stop()
            tts?.shutdown()
        } catch (_: Exception) {
        }
        restoreMediaVolume()
        tts = null
        ready = false
    }
}