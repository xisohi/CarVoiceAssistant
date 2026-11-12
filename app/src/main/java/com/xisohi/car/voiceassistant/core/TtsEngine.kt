package com.xisohi.car.voiceassistant.core

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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

    /** 预热合成专用 utteranceId 前缀：onStart/onDone 据此跳过 listener，避免污染服务状态机 */
    private val PREWARM_PREFIX = "prewarm-"

    private var tts: TextToSpeech? = null
    @Volatile
    private var ready = false
    private val appContext = context.applicationContext
    private var currentEngineIndex = -1
    /** 预热标志：首次合成（加载声库）已触发，避免唤醒时 TTS 冷启动出声慢 */
    private var prewarmed = false

    // ===== 播报看门狗：引擎不回调 onDone 时主动收尾 =====
    // 背景：调用方（VoiceAssistantService）完全依赖 onSpeakDone 推进状态机。
    // 一旦引擎不回调（合成被拒 / 引擎进程被杀 / 驱动异常），服务会永久卡在 SPEAKING，
    // 唤醒监听不再启动、TTS 音频焦点不再释放，只能杀进程恢复。
    // 兜底：按文本长度估算播报时长，到点自行收尾（释放焦点 + onSpeakDone）。
    // 回调来自主线程（TextToSpeech 在服务 onCreate 的主线程构造，其内部 Handler 绑定该线程）。
    private val handler = Handler(Looper.getMainLooper())

    /** 当前进行中的播报 id；null = 已收尾（看门狗据此判断是否还需要兜底） */
    @Volatile
    private var activeUtteranceId: String? = null

    /** 最近一次播报文本长度，用于估算看门狗时长 */
    private var lastSpokenLength = 0

    private val speakWatchdog = Runnable {
        val id = activeUtteranceId ?: return@Runnable
        Log.w("TtsEngine", "TTS 播报超时未收到 onDone（id=$id），主动收尾（释放焦点 + 回调 onSpeakDone）")
        activeUtteranceId = null
        releaseAudioFocus()
        listener?.onSpeakDone()
    }

    /** 看门狗时长：1.5x 语速约 250ms/字，加 3 秒余量，限幅 [5s, 30s] */
    private fun watchdogMs(): Long =
        (3_000L + lastSpokenLength * 250L).coerceIn(5_000L, 30_000L)

    /** 布防/重新布防看门狗（onStart 时重新计时，冷启动延迟不吃播报预算） */
    private fun armWatchdog() {
        handler.removeCallbacks(speakWatchdog)
        handler.postDelayed(speakWatchdog, watchdogMs())
    }

    /** 撤防看门狗并标记本次播报已收尾 */
    private fun cancelWatchdog() {
        handler.removeCallbacks(speakWatchdog)
        activeUtteranceId = null
    }

    // 音频焦点管理：TTS 播报时请求音频焦点，让音乐自动降低音量（duck）
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var audioFocusRequest: AudioFocusRequest? = null
    private var hasAudioFocus = false

    // ===== 播报期诊断采样（2026-11-12 新增，只写日志，不改行为） =====
    // 目的：区分"根本没出声"与"出声了但被系统/车机吞掉"。
    // 依据：requestAudioFocus() 返回 FAILED 时旧代码仍继续 speak，
    //       若导航 App 正持独占焦点，会出现 onStart/onDone 正常却完全无声。
    private var volumeSampler: Runnable? = null
    private var sampleCount = 0

    /**
     * 提示音输出通道：false = 媒体流(流3) + 音量保底；true = 通知流(流5)。
     * 媒体流通路会被导航 duck（压到 1/15），通知流是绕行方案但满格只有 12。
     */
    private val USE_NOTIFICATION_STREAM = false

    // ===== 播报期音量保底（2026-11-12）=====
    // 背景（用户实测）：导航 duck 媒体音量后留下 1/15，且只有"下一次导航完整播报"才会恢复 15。
    // 于是助手从 1 起步播报听不见；播报中导航再压一次，声音突然变小。
    private var promptVolBackup: Int? = null
    private var promptVolRaised = false

    private fun promptStream(): Int =
        if (USE_NOTIFICATION_STREAM) AudioManager.STREAM_NOTIFICATION else AudioManager.STREAM_MUSIC

    private fun promptFloor(stream: Int): Int {
        val max = audioManager.getStreamMaxVolume(stream)
        return (max * 0.8f).toInt().coerceAtLeast(1)
    }

    /** 播报前抬到下限（满格 80%），并记下被抬前的原值 */
    private fun raiseForPrompt() {
        val stream = promptStream()
        val max = audioManager.getStreamMaxVolume(stream)
        val cur = audioManager.getStreamVolume(stream)
        val floor = promptFloor(stream)
        if (cur < floor) {
            promptVolBackup = cur
            promptVolRaised = true
            audioManager.setStreamVolume(stream, floor, 0)
            LogUtils.d("TtsEngine", "播报前抬升流$stream: $cur → $floor（回读=${audioManager.getStreamVolume(stream)}）")
        } else {
            promptVolBackup = null
            promptVolRaised = false
        }
    }

    /** 播报中被外部压低（导航 duck）时补抬，由 200ms 采样调用 */
    private fun guardPromptVolume() {
        if (!promptVolRaised) return
        val stream = promptStream()
        val floor = promptFloor(stream)
        val cur = audioManager.getStreamVolume(stream)
        if (cur < floor) {
            audioManager.setStreamVolume(stream, floor, 0)
            LogUtils.w("TtsEngine", "播报期流$stream 被压到 $cur，已补抬到 $floor")
        }
    }

    /**
     * 播报结束还原。
     * ★ 关键：原值若异常低（<60% 满格）= 导航 duck 留下的中间值，不写回，直接还给满格。
     *   否则把 1 固化成"正常值"，导航不完整播报一次就永远卡在 1。
     * ★ 必须在 listener.onSpeakDone() 之前调用：之后服务会走 muteMediaVolume()，
     *   它会把当前值当"原始音量"存下（顺序反了会静默改掉音量）。
     */
    private fun restoreAfterPrompt() {
        if (!promptVolRaised) return
        val backup = promptVolBackup
        if (backup == null) {
            promptVolRaised = false
            return
        }
        val stream = promptStream()
        val max = audioManager.getStreamMaxVolume(stream)
        val abnormal = backup < (max * 0.6f).toInt()
        val target = if (abnormal) max else backup
        audioManager.setStreamVolume(stream, target, 0)
        LogUtils.d("TtsEngine", "播报后还原流$stream: $target（原值=$backup, ${if (abnormal) "原值异常低已还原满格" else "还原原值"}，回读=${audioManager.getStreamVolume(stream)}）")
        promptVolRaised = false
        promptVolBackup = null
    }

    private fun streamMusicState(): String {
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val cur = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        val maxN = audioManager.getStreamMaxVolume(AudioManager.STREAM_NOTIFICATION)
        val curN = audioManager.getStreamVolume(AudioManager.STREAM_NOTIFICATION)
        return "流3=${cur}/${max}, 流5=${curN}/${maxN}, hasFocus=$hasAudioFocus, isMusicActive=${audioManager.isMusicActive}"
    }

    private fun startVolumeSampling() {
        stopVolumeSampling()
        sampleCount = 0
        val sampler = object : Runnable {
            override fun run() {
                sampleCount++
                guardPromptVolume()
                LogUtils.d("TtsEngine", "[播报采样#${sampleCount}] ${streamMusicState()}")
                if (sampleCount < 10) handler.postDelayed(this, 200)
            }
        }
        volumeSampler = sampler
        handler.postDelayed(sampler, 0)
    }

    private fun stopVolumeSampling() {
        volumeSampler?.let { handler.removeCallbacks(it) }
        volumeSampler = null
    }

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
                    // ★ 2026-11-12：默认回到【媒体流】USAGE_MEDIA，配合播报期音量保底。
                    //   导航用途（11-09 实验）已证实在导航播报时同样被压到 1，没有增益。
                    //   把 USE_NOTIFICATION_STREAM 改 true 可切通知流做对照。
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                        currentTts.setAudioAttributes(
                            AudioAttributes.Builder()
                                .setUsage(
                                    if (USE_NOTIFICATION_STREAM) AudioAttributes.USAGE_NOTIFICATION
                                    else AudioAttributes.USAGE_MEDIA
                                )
                                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                                .build()
                        )
                    }
                    setupListener(currentTts)
                    Log.d("TtsEngine", "TTS 就绪 ✅")
                    // ★ 预热：TTS 引擎首次合成需加载声库/模型（车机上可达 300~800ms），
                    // 若等用户唤醒时才首次 speak，会显著拉长"唤醒→提示音出声"延迟。
                    // 服务启动即用零音量合成一次，静默加载声库；正式播报时直接出声。
                    prewarm(currentTts)
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
                // 预热合成不回调 listener（避免污染服务状态机：currentState=SPEAKING）
                if (utteranceId?.startsWith(PREWARM_PREFIX) == true) return
                // ★ 真正开始出声时重新布防：冷启动延迟不计入播报预算
                LogUtils.d("TtsEngine", "onStart 开始出声，${streamMusicState()}")
                armWatchdog()
                listener?.onSpeakStart()
            }

            override fun onDone(utteranceId: String?) {
                // 预热合成不回调 listener（避免误触发 resumeWake() 等流程）
                if (utteranceId?.startsWith(PREWARM_PREFIX) == true) return
                stopVolumeSampling()
                LogUtils.d("TtsEngine", "onDone 播报结束，${streamMusicState()}")
                cancelWatchdog()  // ★ 正常收尾，撤防看门狗
                restoreAfterPrompt()  // ★ 必须在 onSpeakDone 之前还原，否则服务静音会拍到抬升值
                // TTS 播报完成，释放音频焦点，音乐恢复正常音量
                releaseAudioFocus()
                listener?.onSpeakDone()
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                // 出错时也释放音频焦点
                stopVolumeSampling()
                LogUtils.w("TtsEngine", "onError 播报出错，${streamMusicState()}")
                restoreAfterPrompt()  // ★ 异常也要还原，避免抬升值残留
                releaseAudioFocus()
                if (utteranceId?.startsWith(PREWARM_PREFIX) == true) return
                cancelWatchdog()  // ★ 异常收尾，撤防看门狗
                listener?.onSpeakDone()
            }
        })
    }

    val isReady: Boolean get() = ready

    /**
     * 静默预热：以 0 音量合成一次短文本，让 TTS 引擎完成声库/模型加载。
     * 播放延迟（从 speak() 到真正出声）是车机唤醒反馈慢的主因之一，
     * 预热后首次正式 speak 直接出声，避免用户以为没唤醒。
     */
    private fun prewarm(engine: TextToSpeech?) {
        if (prewarmed || engine == null) return
        try {
            val params = Bundle()
            params.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 0.0f)  // 静音合成，不出声
            // 预热文本必须与 strings.xml 的 tts_wake_prompt 保持一致：
            // 预热时已合成过的这段文本在引擎内就绪，正式播报可直接出声；
            // 文本不一致会退化成"重新合成"，首字延迟回到 300~800ms。
            val result = engine.speak("主人您请说", TextToSpeech.QUEUE_FLUSH, params, "$PREWARM_PREFIX${UUID.randomUUID()}")
            // 只在调用成功后置 true；失败（返回 ERROR 或抛异常）保持 false，下次 init 可重试
            prewarmed = result == TextToSpeech.SUCCESS
            if (prewarmed) {
                Log.d("TtsEngine", "TTS 已预热（静音合成，消除首次出声延迟）")
            } else {
                Log.w("TtsEngine", "TTS 预热失败（speak 返回 ERROR=${result}），下次 init 将重试")
            }
        } catch (e: Exception) {
            Log.w("TtsEngine", "TTS 预热失败: ${e.message}")
        }
    }

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
        lastSpokenLength = text.length
        activeUtteranceId = utteranceId
        raiseForPrompt()
        LogUtils.d("TtsEngine", "开始播报'$text'（${text.length}字），${streamMusicState()}")
        val result = tts?.speak(text, TextToSpeech.QUEUE_FLUSH, params, utteranceId)
        if (result == null || result == TextToSpeech.ERROR) {
            // ★ 引擎当场拒绝合成（tts 为 null 或返回 ERROR）：不会再有 onStart/onDone，
            //   立即按"播报完成"收尾，否则调用方状态机会卡死在 SPEAKING。
            LogUtils.w("TtsEngine", "TTS 未受理本次合成（result=$result），立即按完成处理")
            cancelWatchdog()
            releaseAudioFocus()
            listener?.onSpeakDone()
            return
        }
        startVolumeSampling()
        armWatchdog()
    }

    /**
     * 请求音频焦点：TTS 播报时让音乐自动降低音量（duck）
     * 使用 AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK，系统会让其他音频降低音量
     * TTS 用完整的系统音量播放，音乐自动降到 15% 左右
     * ★ 2026-10-16：attrs 从导航用途改回媒体用途（同 setAudioAttributes 的回退）——
     *   不再以"导航"身份申请任何焦点，避免车机导航通道状态错乱。
     */
    private fun requestAudioFocus() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                // API 26+ 使用 AudioFocusRequest
                if (audioFocusRequest == null) {
                    val audioAttributes = AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                    audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                        .setAudioAttributes(audioAttributes)
                        .setWillPauseWhenDucked(false)
                        .build()
                }
                val result = audioManager.requestAudioFocus(audioFocusRequest!!)
                hasAudioFocus = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
                LogUtils.d("TtsEngine", "请求音频焦点: ${if (hasAudioFocus) "成功" else "失败"}（result=$result）")
            } else {
                // API 26 以下使用旧 API
                @Suppress("DEPRECATION")
                val result = audioManager.requestAudioFocus(
                    null,
                    AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
                )
                hasAudioFocus = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
                LogUtils.d("TtsEngine", "请求音频焦点(旧API): ${if (hasAudioFocus) "成功" else "失败"}（result=$result）")
            }
        } catch (e: Exception) {
            LogUtils.w("TtsEngine", "请求音频焦点失败: ${e.message}")
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
        stopVolumeSampling()
        cancelWatchdog()  // ★ 避免看门狗与下面的 onSpeakDone 重复触发
        restoreAfterPrompt()  // ★ 打断播报也要还原音量
        releaseAudioFocus()
        listener?.onSpeakDone()
    }

    fun shutdown() {
        // ★ 服务销毁：先撤防看门狗并摘掉 listener，避免销毁后回调已失效的服务实例
        stopVolumeSampling()
        restoreAfterPrompt()  // ★ 兜底：播报中被杀不能把音量留在抬升值
        cancelWatchdog()
        listener = null
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
