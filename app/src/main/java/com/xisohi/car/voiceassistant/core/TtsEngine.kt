package com.xisohi.car.voiceassistant.core

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
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

    // 音频焦点管理：TTS 播报时请求音频焦点，让音乐自动降低音量（duck）
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var audioFocusRequest: AudioFocusRequest? = null
    private var hasAudioFocus = false

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
                // TTS 播报完成，释放音频焦点，音乐恢复正常音量
                releaseAudioFocus()
                listener?.onSpeakDone()
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                // 出错时也释放音频焦点
                releaseAudioFocus()
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
        // 请求音频焦点：TTS 播报时让音乐自动降低音量（duck）
        // TTS 自身音量保持系统音量不变（50%），音乐自动降到 15% 左右
        requestAudioFocus()
        // 设置 TTS 自身音量为最大（1.0）
        val params = Bundle()
        params.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f)
        params.putFloat(TextToSpeech.Engine.KEY_PARAM_PAN, 0.0f)  // 左右声道平衡
        val utteranceId = UUID.randomUUID().toString()
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, params, utteranceId)
    }

    /**
     * 请求音频焦点：TTS 播报时让音乐自动降低音量（duck）
     * 使用 AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK，系统会让其他音频降低音量
     * TTS 用完整的系统音量播放，音乐自动降到 15% 左右
     */
    private fun requestAudioFocus() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                // API 26+ 使用 AudioFocusRequest
                if (audioFocusRequest == null) {
                    val audioAttributes = AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                    audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                        .setAudioAttributes(audioAttributes)
                        .setWillPauseWhenDucked(false)
                        .build()
                }
                val result = audioManager.requestAudioFocus(audioFocusRequest!!)
                hasAudioFocus = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
                Log.d("TtsEngine", "请求音频焦点: ${if (hasAudioFocus) "成功" else "失败"}")
            } else {
                // API 26 以下使用旧 API
                @Suppress("DEPRECATION")
                val result = audioManager.requestAudioFocus(
                    null,
                    AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
                )
                hasAudioFocus = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
                Log.d("TtsEngine", "请求音频焦点(旧API): ${if (hasAudioFocus) "成功" else "失败"}")
            }
        } catch (e: Exception) {
            Log.w("TtsEngine", "请求音频焦点失败: ${e.message}")
        }
    }

    /**
     * 释放音频焦点：TTS 播报完成后音乐恢复正常音量
     */
    private fun releaseAudioFocus() {
        try {
            if (!hasAudioFocus) return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                audioFocusRequest?.let {
                    audioManager.abandonAudioFocusRequest(it)
                }
            } else {
                @Suppress("DEPRECATION")
                audioManager.abandonAudioFocus(null)
            }
            hasAudioFocus = false
            Log.d("TtsEngine", "释放音频焦点，音乐恢复正常音量")
        } catch (e: Exception) {
            Log.w("TtsEngine", "释放音频焦点失败: ${e.message}")
        }
    }

    fun stopSpeaking() {
        tts?.stop()
        releaseAudioFocus()
        listener?.onSpeakDone()
    }

    fun shutdown() {
        try {
            tts?.stop()
            tts?.shutdown()
        } catch (_: Exception) {
        }
        releaseAudioFocus()
        tts = null
        ready = false
    }
}
