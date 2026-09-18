package com.xisohi.car.voiceassistant.core

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.os.Handler
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import com.xisohi.car.voiceassistant.R
import com.xisohi.car.voiceassistant.core.wakeword.WakeWordEngine  // 使用您指定的包
import com.xisohi.car.voiceassistant.download.ModelManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import android.media.ToneGenerator
import android.media.AudioManager
import com.xisohi.car.voiceassistant.core.monitor.NetworkMonitor
import com.xisohi.car.voiceassistant.core.monitor.PhoneStateMonitor

class VoiceAssistantService : Service() {

    enum class State { IDLE, LISTENING, PROCESSING, SPEAKING }


    /**
     * 音频帧数据类（生产者-消费者模式用）
     * 录音线程只负责读音频和封装成 AudioFrame 入队，识别线程负责所有处理
     * 注意：不用 data class，因为 data class 自动生成的 equals() 对 ShortArray 用引用比较，
     * 可能导致 queue.contains()/remove() 行为错误。这里只需要数据容器，用普通 class。
     */
    private class AudioFrame(
        val data: ShortArray,
        val length: Int
    )

    companion object {
        const val ACTION_START = "com.xisohi.car.voiceassistant.action.START"
        const val ACTION_STOP = "com.xisohi.car.voiceassistant.action.STOP"
        const val ACTION_WAKE_TRIGGER = "com.xisohi.car.voiceassistant.action.WAKE_TRIGGER"
        // 识别结果广播 Action（用于通知 MainActivity 显示到运行日志）
        const val ACTION_RECOGNITION_LOG = "com.xisohi.car.voiceassistant.ACTION_RECOGNITION_LOG"
        const val EXTRA_LOG_MESSAGE = "log_message"
        private const val CHANNEL_ID = "voice_assistant"
        private const val NOTIF_ID = 1
        private const val MAX_RECORD_MS = 20_000L  // 最长录音 20 秒（给用户足够时间说话）
        private const val MIN_RECORD_MS = 2_000L    // 最短录音 2 秒（避免短暂停顿被误判为端点）
        private const val MAX_SILENCE_MS = 2000L     // 连续静音超过 2000ms 才认为用户说完了（避免说话中间间隙误判）
        private const val WAIT_SPEECH_TIMEOUT_MS = 8_000L  // 用户开口前的等待上限（8秒没有效声音就自动退出，避免无限循环"没有听清"）
        private const val EXTERNAL_NAV_PAUSE_MS = 30_000L   // 拉起支持语音选择的导航后，暂停唤醒监听的时长（给导航让出麦克风）

        // ============================================================
        // 【百度语音识别增益调整位置】
        // 百度语音识别没有 AGC 自动增益和降噪功能（经官方文档确认），
        // 音量过小会导致 ERROR_SPEECH_QUALITY 错误或识别率下降，需要本地放大。
        //
        // 调整建议：
        //   2.0f = 保守，几乎不会削顶，识别率略有提升
        //   2.5f = 推荐，信噪比明显提升，大部分场景不会削顶（当前值）
        //   3.0f = 较激进，大声说话可能轻微削顶，识别率更好
        //   4.0f+ = 不建议，容易削顶失真
        // ============================================================
        private const val BAIDU_GAIN = 2.5f          // 百度在线识别用的轻量增益（修改这里调整百度识别音量）

        // ===== 自适应静音阈值参数 =====
        // 不再使用固定阈值，改为启动时采样环境噪音动态计算
        // 公式：adaptiveThreshold = ambientRms * NOISE_MULTIPLIER，并限制在 [MIN, MAX] 区间
        // 注意：车机环境通常很安静（环境噪音 RMS 50-150），下限不能设太高，
        // 否则正常说话的轻音（辅音、轻声）会被误判为静音，导致录音只录前半段。
        private const val SILENCE_RMS_MIN = 150f      // 绝对下限（安静停车环境，之前500太高导致轻音被误判）
        private const val SILENCE_RMS_MAX = 1200f     // 绝对上限（防止噪音过大导致阈值过高）
        private const val NOISE_MULTIPLIER = 2.0f     // 环境噪音倍数（稍微提高，让安静环境下阈值更合理）
        private const val NOISE_WARMUP_MS = 100L      // 丢弃前 100ms（录音启动爆音）
        private const val NOISE_SAMPLE_MS = 300L      // 环境噪音采样时长

        @Volatile
        private var instance: VoiceAssistantService? = null

        /**
         * 取消外部导航暂停，立即恢复唤醒监听。
         * 用户点击悬浮球手动唤醒时调用（FloatViewService.triggerWake() 里调用）。
         * 如果TTS正在播报导航，会停止TTS让onSpeakDone()立即执行导航（不取消导航）。
         */
        fun cancelExternalNavPause() {
            instance?.cancelExternalNavPause()
        }

        @Volatile
        var currentState: State = State.IDLE
            private set

        @Volatile
        var lastRecognizedText: String = ""
            private set

        // 当前录音的 RMS 值（实时值，录音结束后停留在最后一帧）
        @Volatile
        var currentRms: Int = 0
            private set

        // 本次录音的 RMS 峰值（设置页显示这个值，车机上看不到日志，峰值更有意义）
        // 下次录音开始时重置为0
        @Volatile
        var peakRms: Int = 0
            private set

        @Volatile
        var lastPartialText: String = ""
            private set

        @Volatile
        var lastIntentResult: String = ""
            private set

        val isRunning: Boolean get() = instance != null

        fun start(context: Context) {
            val intent = Intent(context, VoiceAssistantService::class.java).setAction(ACTION_START)
            if (Build.VERSION.SDK_INT >= 26) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            instance?.stopSelf()
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var wakeWordEngine: WakeWordEngine
    private lateinit var intentParser: IntentParser
    private var toneGenerator: ToneGenerator? = null

    // ---------- 百度流式识别相关（线程安全） ----------
    /**
     * 百度检测到说话结束的标志（asr.end 事件触发）
     * 必须用 AtomicBoolean，不能用普通局部变量！
     * 原因：百度回调线程写，IO 录音线程读，普通变量没有 happens-before 保证，
     * JIT 可能把变量缓存在寄存器里，导致录音循环永远看不到 true，直到 20 秒超时。
     */
    private val shouldStopRecording = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * 百度 asr.end 触发的时间（用于继续喂 400ms 尾部音频）
     * asr.end 之后，百度还需要 ~200ms 才会吐 final_result，
     * 这期间应该继续喂音频，否则最后一个字的尾音可能丢失。
     */
    @Volatile
    private var speechEndedAt: Long = 0L
    /** 标记是否正在播放唤醒提示音（TTS说"在呢，您请说"），用于 onSpeakDone 中区分 */
    private var isWakePromptSpeaking = false

    // ---------- 音量控制（唤醒时自动降低媒体音量，减少背景噪音，提高识别率） ----------
    /** 保存唤醒前的原始媒体音量，识别完成后恢复 */
    private var originalMediaVolume: Int = -1
    /** 标记是否已经降低了媒体音量 */
    private var isMediaVolumeMuted = false
    // 没听懂后是否需要重新监听（true=TTS说完后直接开始录音，不需要唤醒词）
    private var isRetryListening = false

    // 待执行的导航意图（TTS播完后再拉起高德，避免TTS和高德语音同时响）
    private var pendingNavIntent: VoiceIntent? = null
    // 拉起支持语音选择的导航后，TTS播完触发暂停唤醒监听（给导航让出麦克风）
    @Volatile
    private var pendingExternalNavPause = false
    private var externalNavPauseRunnable: Runnable? = null  // 外部导航暂停的恢复定时器

    // 本次识别用户是否开口了（用于区分"没开口超时"和"开口了但没识别到内容"）
    private var hasSpeechStartedThisSession = false
    // 连续没说话的重试次数（唤醒后长时间不说话，连续2次就自动退出，避免无限循环"没有听清"）
    private var noSpeechRetryCount = 0
    private val MAX_NO_SPEECH_RETRY = 2  // 最多重试2次，第3次就自动退出
    // 标记本次识别是否是"在线识别失败后回退到离线"的场景
    // 用于防止无限循环：在线失败→回退离线→离线失败→又调用startRecognition()→又选在线→又失败...
    private var isFallbackFromOnline = false
    // 连续在线识别失败次数（用于网络不通或Key错误时自动降级为离线，避免每次都要等在线超时）
    private var consecutiveOnlineFailures = 0
    private val MAX_CONSECUTIVE_ONLINE_FAILURES = 3  // 连续失败3次后暂时降级为离线
    private val ONLINE_RECOGNITION_TIMEOUT_MS = 15_000L  // 在线识别超时15秒（网络不通时百度可能一直不返回）
    // 在线识别超时定时器（用于取消超时回调）
    private var onlineTimeoutRunnable: Runnable? = null
    // 标记在线识别是否已经收到结果（避免超时和回调同时触发）
    private var onlineResultReceived = false
    // 网络监控器（网络状态监听、连通性检测、缓存管理）
    private lateinit var networkMonitor: NetworkMonitor

    // 电话状态监控：通话中暂停唤醒监听，通话结束后恢复
    private val phoneStateMonitor = PhoneStateMonitor(this, object : PhoneStateMonitor.Callback {
        override fun onCallStarted() {
            if (isWakeListening) {
                android.util.Log.d("VoiceService", "电话中，暂停唤醒监听")
                stopWakeListening()
            }
        }
        override fun onCallEnded() {
            if (!isWakeListening) {
                android.util.Log.d("VoiceService", "电话结束，恢复唤醒监听")
                startWakeListening()
            }
        }
    })
    // 网络变化防抖：5秒内的连续回调只处理一次（避免WiFi/4G切换时频繁清空缓存）
    private lateinit var skillExecutor: SkillExecutor
    private lateinit var ttsEngine: TtsEngine
    private lateinit var placeMatcher: PlaceMatcher  // 地名模糊匹配器（导航同音字纠正）
    private lateinit var baiduAsrManager: BaiduAsrManager  // 百度语音识别（在线，识别率更高）

    private var recognitionJob: Job? = null
    // 小模型加载快（<1秒），不需要预加载，每次识别时直接创建即可

    // 唤醒音频采集线程
    private var wakeAudioThread: WakeAudioThread? = null
    @Volatile
    private var isWakeListening = false

    override fun onCreate() {
        super.onCreate()
        instance = this
        // 初始化网络监控器
        networkMonitor = NetworkMonitor(this, scope)
        // 注册网络变化监听：开/关热点、进/出隧道时触发，清空网络缓存
        networkMonitor.start(object : NetworkMonitor.Callback {
            override fun onNetworkAvailable() {
                // 网络已连接，NetworkMonitor 内部会延迟1秒后异步检测
            }
            override fun onNetworkLost() {
                // 网络已断开，NetworkMonitor 内部会清空缓存
            }
        })

        // 注册电话状态监听：通话中暂停唤醒监听，避免麦克风冲突和误唤醒
        phoneStateMonitor.start()

        currentState = State.IDLE
        createChannel()

        // 初始化官方 WakeWordEngine（构造函数自动加载 model_info.json）
        wakeWordEngine = WakeWordEngine(this)
        if (!wakeWordEngine.isLoaded) {
            android.util.Log.e("VoiceService", "唤醒引擎加载失败: ${wakeWordEngine.errorMessage}")
        }
        // 读取保存的灵敏度配置
        val prefs = getSharedPreferences("voice_assistant_prefs", MODE_PRIVATE)
        val savedSens = prefs.getInt("wake_sensitivity", 1)
        WakeWordEngine.setSensitivity(savedSens)
        android.util.Log.i("VoiceService", "唤醒灵敏度: ${WakeWordEngine.getSensitivityName()} (增益=${WakeWordEngine.getAudioGain()}, 阈值=${WakeWordEngine.getDetectionThreshold()})")

        // 读取保存的识别增益（asrGain），避免服务重启后用户设置丢失
        val savedAsrGain = prefs.getFloat("asr_gain_override", 8.0f)
        WakeWordEngine.setAsrGain(savedAsrGain)
        android.util.Log.i("VoiceService", "识别增益: ${WakeWordEngine.getAsrGain()}")

        // 初始化录音保存工具（保存最近10条录音，方便回听判断录音质量）
        AudioSaver.init(this)

        // 初始化百度语音识别管理器（有网络且配置了 Key 时优先使用百度，识别率更高）
        baiduAsrManager = BaiduAsrManager.getInstance(this)
        if (baiduAsrManager.isConfigured()) {
            android.util.Log.i("VoiceService", "百度语音已配置，有网络时优先使用百度识别")
        } else {
            android.util.Log.i("VoiceService", "百度语音未配置，使用离线 Vosk 识别")
        }

        // 预加载 Vosk 语音识别模型（后台线程，不阻塞服务启动）
        // 这样第一次唤醒识别时不需要等 2-3 秒模型加载，车机上从唤醒到录音可从 5 秒降到 1 秒内
        scope.launch(Dispatchers.IO) {
            try {
                val modelDir = ModelManager.findAsrModelDir(this@VoiceAssistantService)
                if (modelDir != null) {
                    SpeechRecognizer.preload(modelDir)
                    android.util.Log.i("VoiceService", "Vosk 模型预加载完成: ${modelDir.name}")
                } else {
                    android.util.Log.w("VoiceService", "Vosk 模型目录未找到，跳过预加载")
                }
            } catch (e: Exception) {
                android.util.Log.e("VoiceService", "Vosk 模型预加载失败", e)
            }
        }

        intentParser = IntentParser(this)
        skillExecutor = SkillExecutor(this)
        placeMatcher = PlaceMatcher(this)
        ttsEngine = TtsEngine(this).apply {
            listener = object : TtsEngine.Listener {
                override fun onSpeakStart() {
                    currentState = State.SPEAKING
                }

                override fun onSpeakDone() {
                    // 如果已经是 IDLE 且没有待处理标志，说明是重复回调，忽略
                    // 注意：pendingNavIntent 和 pendingExternalNavPause 也是待处理标志，不能忽略
                    if (currentState == State.IDLE && !isWakePromptSpeaking && !isRetryListening
                        && pendingNavIntent == null && !pendingExternalNavPause) {
                        return
                    }
                    // 如果是唤醒提示音（"在呢，您请说"）刚说完，开始录音识别用户指令
                    if (isWakePromptSpeaking) {
                        isWakePromptSpeaking = false
                        android.util.Log.d("VoiceService", "唤醒提示音播报完成，开始录音识别")
                        // 延迟 50ms 再开始录音，确保 TTS 完全停止，不被录进语音指令
                        // 录音时其他声音降到0（静音），提高识别准确率
                        mainHandler.postDelayed({
                            muteMediaVolume()
                            startRecognition()
                        }, 50)
                        return
                    }
                    // 如果是"没听懂，请重说"刚说完，直接重新监听（不需要唤醒词）
                    if (isRetryListening) {
                        isRetryListening = false
                        android.util.Log.d("VoiceService", "没听懂提示音播报完成，重新开始录音识别")
                        // 延迟 50ms 再开始录音，确保 TTS 完全停止
                        // 录音时其他声音降到0（静音），提高识别准确率
                        mainHandler.postDelayed({
                            muteMediaVolume()
                            startRecognition()
                        }, 50)
                        return
                    }
                    // 正常回复播报完成
                    currentState = State.IDLE
                    // ★ 如果有待执行的导航意图（TTS播完后再拉起高德），先执行拉起高德
                    pendingNavIntent?.let { navIntent ->
                        LogUtils.i("VoiceService", "TTS 播报完成，拉起导航")
                        pendingNavIntent = null
                        try {
                            val result = skillExecutor.execute(navIntent)
                            if (result.needsMicPause) {
                                pendingExternalNavPause = true
                                LogUtils.i("VoiceService", "导航应用支持语音选择，TTS播完后将暂停唤醒监听给导航让麦")
                            }
                        } catch (e: Exception) {
                            LogUtils.w("VoiceService", "拉起导航失败: ${e.message}")
                        }
                    }
                    // 如果拉起了支持语音选择的导航，暂停唤醒监听给导航让麦；否则直接恢复
                    if (pendingExternalNavPause) {
                        pendingExternalNavPause = false
                        pauseWakeForExternalNav()
                    } else {
                        resumeWake()
                    }
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                // 服务停止时确保恢复媒体音量
                restoreMediaVolume()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_WAKE_TRIGGER -> {
                if (currentState == State.IDLE) {
                    onWakeWord()
                }
                return START_STICKY
            }
        }
        startForegroundCompat()
        currentState = State.IDLE
        resumeWake()
        try {
            android.util.Log.d("VoiceAssistant", "启动悬浮窗服务...")
            FloatViewService.start(this)
        } catch (e: Exception) {
            android.util.Log.w("VoiceAssistant", "启动悬浮窗服务失败: ${e.message}")
        }
        return START_STICKY
    }

    private fun startForegroundCompat() {
        val notification = buildNotification(getString(R.string.notification_running))
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    private fun buildNotification(text: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_mic)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setOngoing(true)
            .build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(CHANNEL_ID, getString(R.string.notification_channel_name), NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    // ---------- 唤醒监听 ----------
    /**
     * 拉起支持语音选择的导航后，暂停唤醒监听，给导航让出麦克风。
     * 导航要自己开麦听用户说"选1"，如果我们还占着麦克风，导航听不到。
     * 30秒后自动恢复唤醒监听，或用户点击悬浮球提前恢复（调用 cancelExternalNavPause()）。
     */
    private fun pauseWakeForExternalNav() {
        LogUtils.i("VoiceService", "拉起外部导航，暂停唤醒监听 ${EXTERNAL_NAV_PAUSE_MS}ms（给导航让出麦克风）")
        stopWakeListening()
        externalNavPauseRunnable?.let { mainHandler.removeCallbacks(it) }
        externalNavPauseRunnable = null
        currentState = State.IDLE

        externalNavPauseRunnable?.let { mainHandler.removeCallbacks(it) }
        externalNavPauseRunnable = Runnable {
            LogUtils.i("VoiceService", "外部导航暂停时间到，恢复唤醒监听")
            externalNavPauseRunnable = null
            resumeWake()
        }
        mainHandler.postDelayed(externalNavPauseRunnable!!, EXTERNAL_NAV_PAUSE_MS)
    }

    /**
     * 取消外部导航暂停，立即恢复唤醒监听。
     * 用户点击悬浮球手动唤醒时调用（FloatViewService.triggerWake() 里调用）。
     *
     * 特殊处理：如果TTS正在播报导航（pendingNavIntent != null），停止TTS让onSpeakDone()
     * 立即执行导航——用户点击悬浮球是"别啰嗦了直接导"，不是"取消导航"。
     */
    fun cancelExternalNavPause() {
        val wasPending = pendingExternalNavPause

        // ★ 如果有待执行的导航意图且 TTS 正在播报，停止 TTS，让 onSpeakDone() 正常执行导航
        if (pendingNavIntent != null && currentState == State.SPEAKING) {
            LogUtils.i("VoiceService", "用户手动唤醒，停止 TTS，立即执行导航（不清 pendingNavIntent）")
            try {
                ttsEngine.stopSpeaking()  // 会触发 onSpeakDone()，执行 pendingNavIntent + 按需暂停
            } catch (e: Exception) {
                LogUtils.w("VoiceService", "停止 TTS 失败，手动执行导航: ${e.message}")
                pendingNavIntent?.let { navIntent ->
                    try {
                        val result = skillExecutor.execute(navIntent)
                        if (result.needsMicPause) pendingExternalNavPause = true
                    } catch (e2: Exception) {
                        LogUtils.w("VoiceService", "拉起导航失败: ${e2.message}")
                    }
                    pendingNavIntent = null
                }
                if (pendingExternalNavPause) {
                    pendingExternalNavPause = false
                    pauseWakeForExternalNav()
                } else {
                    currentState = State.IDLE
                    resumeWake()
                }
            }
            return
        }

        // 没有待执行的导航意图，正常取消外部导航暂停
        pendingExternalNavPause = false
        pendingNavIntent = null

        if (externalNavPauseRunnable != null) {
            mainHandler.removeCallbacks(externalNavPauseRunnable!!)
            externalNavPauseRunnable = null
            LogUtils.i("VoiceService", "用户手动唤醒，取消外部导航暂停，立即恢复唤醒监听")
            resumeWake()
        } else if (wasPending) {
            LogUtils.i("VoiceService", "用户手动唤醒，取消待执行的外部导航暂停（TTS播报中）")
            // 加超时兜底：5秒内如果 onSpeakDone 没触发，主动恢复
            mainHandler.postDelayed({
                if (currentState == State.SPEAKING && !isWakePromptSpeaking && !isRetryListening && !pendingExternalNavPause) {
                    LogUtils.w("VoiceService", "TTS 播报超时，主动恢复唤醒监听")
                    currentState = State.IDLE
                    resumeWake()
                }
            }, 5000)
        }
    }

    private fun resumeWake() {
        if (recognitionJob?.isActive == true) return
        scheduleSubtitleClear(5000)
        startWakeListening()
    }

    private fun startWakeListening() {
        if (isWakeListening) return
        if (!wakeWordEngine.isLoaded) {
            android.util.Log.e("VoiceService", "唤醒引擎未加载，无法启动")
            return
        }
        isWakeListening = true
        wakeAudioThread = WakeAudioThread().apply { start() }
        android.util.Log.d("VoiceService", "唤醒监听已启动")
    }

    private fun stopWakeListening() {
        isWakeListening = false
        val thread = wakeAudioThread
        thread?.interrupt()
        // 等待线程完全结束（最多等待2秒），防止 onDestroy 中关闭 session 后线程还在访问
        try {
            thread?.join(2000)
        } catch (_: InterruptedException) {
        }
        wakeAudioThread = null
        android.util.Log.d("VoiceService", "唤醒监听已停止")
    }

    // 唤醒音频采集与推理线程
    private inner class WakeAudioThread : Thread("WakeAudioThread") {
        override fun run() {
            val sampleRate = 16000
            val channelConfig = AudioFormat.CHANNEL_IN_MONO
            val audioFormat = AudioFormat.ENCODING_PCM_16BIT
            val minBufSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
            if (minBufSize <= 0) {
                android.util.Log.e("WakeAudioThread", "无效的音频参数")
                return
            }

            val record = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                sampleRate,
                channelConfig,
                audioFormat,
                maxOf(minBufSize * 8, 256_000)  // minBuf*8，最小256KB（约8秒缓冲），避免车机CPU慢导致ring buffer溢出丢帧
            )
            if (record.state != AudioRecord.STATE_INITIALIZED) {
                android.util.Log.e("WakeAudioThread", "AudioRecord 初始化失败")
                return
            }

            // 初始化音频降噪（系统降噪 + 高通滤波器）
            val noiseReducer = AudioNoiseReducer.create(record)

            // 官方引擎需要的帧大小（由 engine.audioSamplesNeeded 获取）
            val frameSize = wakeWordEngine.audioSamplesNeeded
            if (frameSize <= 0) {
                android.util.Log.e("WakeAudioThread", "无效的帧大小")
                record.release()
                return
            }

            val audioBuffer = ShortArray(frameSize)
            // ★ 关键：startRecording() 可能因为麦克风被占用而失败，需要重试
            // 场景：百度 SDK 刚释放麦克风，立即启动唤醒监听会失败，重试几次等待麦克风完全释放
            var startSuccess = false
            for (retry in 1..3) {
                try {
                    record.startRecording()
                    // 用 recordingState 判断是否启动成功（startRecording() 返回 Unit，不能直接比较返回值）
                    if (record.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                        startSuccess = true
                        break
                    } else {
                        android.util.Log.w("WakeAudioThread", "startRecording() 失败 (state=${record.recordingState})，第 $retry 次重试...")
                        Thread.sleep(200)
                    }
                } catch (e: Exception) {
                    android.util.Log.w("WakeAudioThread", "startRecording() 异常: ${e.message}，第 $retry 次重试...")
                    Thread.sleep(200)
                }
            }
            if (!startSuccess) {
                android.util.Log.e("WakeAudioThread", "AudioRecord 启动失败（重试3次均失败），麦克风可能被其他应用占用")
                record.release()
                return
            }
            android.util.Log.d("WakeAudioThread", "开始录音，帧大小=$frameSize")

            try {
                while (isWakeListening && !isInterrupted()) {
                    val read = record.read(audioBuffer, 0, frameSize, AudioRecord.READ_BLOCKING)
                    if (read == frameSize) {
                        // 应用降噪处理（高通滤波，去除低频发动机噪音）
                        noiseReducer.process(audioBuffer, read)
                        // process 可能在 service 销毁时访问已关闭的 session，捕获异常防止线程崩溃
                        val result = try {
                            wakeWordEngine.process(audioBuffer)
                        } catch (e: IllegalStateException) {
                            android.util.Log.w("WakeAudioThread", "session 已关闭，停止处理: ${e.message}")
                            break
                        } catch (e: Exception) {
                            android.util.Log.w("WakeAudioThread", "处理异常: ${e.message}")
                            continue
                        }
                        if (result != null && result.wakeWord != null) {
                            android.util.Log.i("WakeAudioThread", "唤醒词检测到: ${result.wakeWord} (${result.probability})")
                            // 触发唤醒回调
                            mainHandler.post {
                                if (currentState == VoiceAssistantService.State.IDLE) {
                                    onWakeWord()
                                }
                            }
                            // 防抖：暂停处理一会儿
                            Thread.sleep(1000)
                        }
                    }
                }
            } catch (e: InterruptedException) {
                // 正常退出
            } finally {
                try { record.stop() } catch (_: Exception) {}
                record.release()
                noiseReducer.release()
                android.util.Log.d("WakeAudioThread", "录音线程结束")
            }
        }
    }

    // ---------- 音量控制 ----------
    /**
     * 降低媒体音量到 0（音乐、导航等），专注听用户说话，提高识别率
     * 第一次调用时保存原始音量，后续调用不会重复保存
     */
    private fun muteMediaVolume() {
        try {
            val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            if (originalMediaVolume < 0) {
                originalMediaVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
            }
            if (!isMediaVolumeMuted) {
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
                isMediaVolumeMuted = true
                android.util.Log.d("VoiceService", "已降低媒体音量到 0（原始音量: $originalMediaVolume），专注听用户说话")
            }
        } catch (e: Exception) {
            android.util.Log.w("VoiceService", "降低媒体音量失败: ${e.message}")
        }
    }

    /**
     * 恢复媒体音量到唤醒前的原始值
     * 识别完成后或 TTS 播报前调用
     */
    private fun restoreMediaVolume() {
        try {
            if (isMediaVolumeMuted && originalMediaVolume >= 0) {
                val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, originalMediaVolume, 0)
                isMediaVolumeMuted = false
                android.util.Log.d("VoiceService", "已恢复媒体音量到: $originalMediaVolume")
                originalMediaVolume = -1
            }
        } catch (e: Exception) {
            android.util.Log.w("VoiceService", "恢复媒体音量失败: ${e.message}")
        }
    }

    // ---------- 唤醒触发 ----------
    private fun onWakeWord() {
        if (currentState != State.IDLE) return
        // 每次唤醒都重置连续没说话的计数
        noSpeechRetryCount = 0
        // 清除旧字幕
        FloatViewService.updateSubtitle("")
        mainHandler.removeCallbacks(clearSubtitleRunnable)
        // 停止唤醒监听
        stopWakeListening()

        // 注意：TTS播报前会恢复音量，让用户听到提示音
        // （已删除开头的 muteMediaVolume()，因为后面马上 restoreMediaVolume()，中间无作用）
        if (ttsEngine.isReady) {
            // TTS 可用：用语音说"在呢，您请说"，更人性化
            // 恢复系统音量到原始值，TTS 播报时请求音频焦点，音乐自动降低（duck）
            // 播报完成后（onSpeakDone）保持原始音量值，不降到0
            restoreMediaVolume()
            // 立即切换悬浮窗为聆听状态（视觉提示，不用等TTS播报完）
            currentState = State.LISTENING
                        isWakePromptSpeaking = true
            ttsEngine.speak(getString(R.string.tts_wake_prompt))
            LogUtils.d("VoiceService", "唤醒提示：TTS播报'在呢，您请说'，播报完成后开始录音")
        } else {
            // TTS 不可用：兜底用哔哔声提示音
            android.util.Log.w("VoiceService", "TTS不可用，使用哔哔声作为唤醒提示")
            // 恢复系统音量到原始值，保证哔哔声够大
            restoreMediaVolume()
            playWakeBeep()
            // 延迟 550ms 再开始录音，确保两声提示音都播放完毕
            // 开始录音前再次降低音量，专注听用户说话
            mainHandler.postDelayed({
                muteMediaVolume()
                startRecognition()
            }, 550)
        }
    }

    /**
     * 播放唤醒提示音：短促响亮的"哔哔"两声
     * 使用系统 ToneGenerator，无需额外音频资源
     * 使用媒体音量通道（车机上媒体音量是主要通道，用户可调节）
     * 播放前检查媒体音量，如果为0则临时调到30%
     */
    private fun playWakeBeep() {
        try {
            val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val currentVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
            val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            android.util.Log.d("VoiceService", "媒体音量: $currentVolume/$maxVolume")

            // 如果媒体音量为0，临时调到30%，确保能听到提示音
            var restoredVolume = -1
            if (currentVolume == 0 && maxVolume > 0) {
                restoredVolume = currentVolume
                val tempVolume = (maxVolume * 0.3).toInt().coerceAtLeast(1)
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, tempVolume, 0)
                android.util.Log.d("VoiceService", "媒体音量为0，临时调到: $tempVolume")
            }

            if (toneGenerator == null) {
                // 使用媒体音量通道，音量 100%（最大）
                toneGenerator = ToneGenerator(AudioManager.STREAM_MUSIC, 100)
            }
            // 播放第一声：TONE_PROP_BEEP 是响亮的"哔"声，时长 200ms（增加时长确保能听到）
            toneGenerator?.startTone(ToneGenerator.TONE_PROP_BEEP, 200)
            // 200ms 后播放第二声（间隔 100ms）
            mainHandler.postDelayed({
                try {
                    toneGenerator?.startTone(ToneGenerator.TONE_PROP_BEEP, 200)
                } catch (_: Exception) {}
            }, 300)

            // 播放完成后恢复原来的音量（如果临时调整过）
            if (restoredVolume >= 0) {
                mainHandler.postDelayed({
                    try {
                        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, restoredVolume, 0)
                        android.util.Log.d("VoiceService", "恢复媒体音量: $restoredVolume")
                    } catch (_: Exception) {}
                }, 800)
            }
        } catch (e: Exception) {
            android.util.Log.w("VoiceService", "播放提示音失败: ${e.message}")
        }
    }

    // ---------- 语音识别 ----------


    /**
     * 识别入口：根据网络状态自动选择识别路径
     * - 有网络且百度已配置 → 百度在线识别（百度自己开麦录音，避免麦克风冲突）
     * 无网络或百度未配置 → Vosk 离线识别（我们自己录音 + Vosk 识别）
     */
    private fun startRecognition() {
        // 正常启动识别时，重置"在线失败回退离线"标志位
        isFallbackFromOnline = false
        // ★ 网络连通性检测：缓存有效时直接用（无延迟），缓存无效时不阻塞（假设网络通，后台异步检测）
        // 缓存失效时机：首次启动、网络变化（开/关热点、进/出隧道）
        // 为什么不阻塞？因为阻塞主线程会导致ANR（输入事件5秒无响应即ANR）
        // 首次唤醒假设网络通，走在线识别；如果实际网络不通，在线失败后自动回退离线（已有机制）
        // 后台异步检测（单URL，2秒超时）完成后更新缓存，后续唤醒用正确的网络状态
        val hasNetwork = networkMonitor.isAvailable()
        val canReachInternet = if (networkMonitor.isCacheValid()) {
            networkMonitor.isReachable()  // 缓存有效，直接用（无延迟）
        } else {
            // 缓存无效 → 不阻塞，假设网络通，同时后台异步检测更新缓存
            if (hasNetwork) {
                networkMonitor.checkReachableAsync()  // 后台异步检测，不阻塞主线程
            } else {
                // 没连网直接标记为不通
                networkMonitor.markUnavailable()
            }
            true  // 假设网络通（走在线识别，失败自动回退离线）
        }
        // 连续在线失败超过阈值时，暂时降级为离线（避免每次都要等在线超时/失败）
        // 适用于：Key错误、网络不通（WiFi已连接但无外网）等场景
        if (consecutiveOnlineFailures >= MAX_CONSECUTIVE_ONLINE_FAILURES) {
            android.util.Log.w("VoiceService", "连续在线识别失败 ${consecutiveOnlineFailures} 次，暂时降级为离线识别（下次启动应用后恢复）")
            sendRecognitionLog("⚠️ 连续在线失败${consecutiveOnlineFailures}次，暂时使用离线识别")
            startRecognitionOffline()
            return
        }
        // 网络连通性判断：hasNetwork 和 canReachInternet 已在上面的同步检测中计算完成
        // 车机场景：WiFi已连接但可能无外网（手机热点没开流量、路由器断网）
        // 配置验证状态（三态）：
        //   ok       = 测试连接通过 → 走在线
        //   error    = 测试连接失败 → 直接走离线（不浪费时间在在线）
        //   untested = 未测试或修改了配置 → 走在线，失败了自动降级（连续失败3次后暂时离线）
        val configStatus = baiduAsrManager.getConfigStatus()
        // 决定是否走在线：配置已配置 + 有网络 + 能访问外网 + 配置状态不是 error
        // （untested 和 ok 都可以走在线，untested 失败后会自动降级）
        val canUseOnline = baiduAsrManager.isConfigured() && hasNetwork && canReachInternet &&
                configStatus != BaiduAsrManager.CONFIG_STATUS_ERROR
        if (canUseOnline) {
            startRecognitionOnline()
        } else {
            when {
                !baiduAsrManager.isConfigured() -> {
                    android.util.Log.d("VoiceService", "百度语音未配置，使用 Vosk 离线识别")
                }
                !hasNetwork -> {
                    android.util.Log.d("VoiceService", "无网络连接，使用 Vosk 离线识别")
                }
                !canReachInternet -> {
                    android.util.Log.w("VoiceService", "网络已连接但无法访问外网，使用 Vosk 离线识别")
                    sendRecognitionLog("⚠️ 网络不通，使用离线识别")
                }
                configStatus == BaiduAsrManager.CONFIG_STATUS_ERROR -> {
                    android.util.Log.w("VoiceService", "百度配置验证失败（请在设置页重新测试连接），使用 Vosk 离线识别")
                    sendRecognitionLog("⚠️ 配置错误，使用离线识别")
                }
            }
            startRecognitionOffline()
        }
    }

    /**
     * 百度在线识别（让百度自己开麦录音）
     *
     * 为什么要让百度自己录？
     * 车机只有一个麦克风，任何时刻只能被一个 AudioRecord 占用。
     * 如果我们自己开 AudioRecord 录音，同时百度 SDK 也想开麦，就会冲突：
     *   AudioRecord: start() status -38
     *   ASREngine: errorCode : 3001 desc : Recorder open failed
     *
     * 正确流程：
     *   唤醒阶段：我们的 AudioRecord 占麦（用于唤醒词检测）
     *       ↓ 检测到唤醒词
     *   识别阶段：先 stopWakeListening() 释放麦克风
     *       ↓
     *   百度 SDK 开麦、自己录、自己 VAD、自己识别
     *       ↓ 百度 asr.finish → 拿到结果
     *   handleText(result)
     *       ↓ 处理完成
     *   resumeWake()，重新开唤醒线程
     *
     * 和百度官方 Demo 完全一致。
     */
    private fun startRecognitionOnline() {
        // 停止唤醒监听，释放麦克风给百度 SDK
        stopWakeListening()
        currentState = State.LISTENING
        android.util.Log.i("VoiceService", "启动百度在线识别（百度自录）")
        sendRecognitionLog("🌐 百度在线识别启动（百度自录）")

        // 重置结果接收标志
        onlineResultReceived = false

        // ★ 超时保护：网络不通时百度 SDK 可能一直不返回回调，15秒后自动回退离线
        val timeoutRunnable = Runnable {
            if (!onlineResultReceived) {
                android.util.Log.w("VoiceService", "百度在线识别超时（${ONLINE_RECOGNITION_TIMEOUT_MS}ms未返回），自动回退到离线识别")
                sendRecognitionLog("⚠️ 在线识别超时，自动回退到离线识别")
                handleOnlineFailure()
            }
        }
        onlineTimeoutRunnable = timeoutRunnable
        mainHandler.postDelayed(timeoutRunnable, ONLINE_RECOGNITION_TIMEOUT_MS)

        // 百度自己开麦、自己 VAD、自己识别
        baiduAsrManager.startStreamingRecognition { result ->
            mainHandler.post {
                // 已经收到结果（或超时已处理），不再重复处理
                if (onlineResultReceived) return@post
                onlineResultReceived = true
                // 取消超时定时器
                onlineTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
                onlineTimeoutRunnable = null

                if (!result.isNullOrEmpty()) {
                    android.util.Log.i("VoiceService", "百度在线识别成功: '$result'")
                    sendRecognitionLog("✅ 百度识别: $result")
                    // 在线识别成功，重置连续失败计数
                    consecutiveOnlineFailures = 0
                    handleText(result)
                } else {
                    android.util.Log.w("VoiceService", "百度在线识别失败或结果为空，自动回退到离线识别")
                    sendRecognitionLog("⚠️ 百度识别失败，自动回退到离线识别")
                    handleOnlineFailure()
                }
            }
        }
    }

    /**
     * 处理在线识别失败：增加失败计数，回退到离线识别
     */
    private fun handleOnlineFailure() {
        // 增加连续失败计数
        consecutiveOnlineFailures++
        android.util.Log.w("VoiceService", "连续在线失败次数: $consecutiveOnlineFailures / $MAX_CONSECUTIVE_ONLINE_FAILURES")
        // ★ 标记：本次是在线失败回退离线，防止离线失败后又重试在线导致无限循环
        isFallbackFromOnline = true
        // ★ 不播报"在线识别失败"，静默降级
        // 用户体验：用户唤醒后说指令，系统应该直接执行，而不是告诉用户"识别失败"
        // 直接走离线识别，用户感知是"多等了几秒"，而不是"被系统告知失败"
        // 延迟800ms，给百度SDK释放麦克风的时间
        val delayMs = 800L
        android.util.Log.d("VoiceService", "延迟 ${delayMs}ms 后启动离线识别（静默降级）")
        mainHandler.postDelayed({
            startRecognitionOffline()
        }, delayMs)
    }

    /**
     * Vosk 离线识别（我们自己录音 + Vosk 识别）
     * 用于无网络或百度未配置时的降级方案。
     */
    private fun startRecognitionOffline() {
        // 重置本次识别的开口标志
        hasSpeechStartedThisSession = false
        // 重置本次录音的 RMS 峰值（设置页显示峰值，下次录音开始时重置）
        peakRms = 0
        // ★★★ 必须重置百度流式识别相关标志！否则上次的 true 会残留，
        // 导致第二轮识别一启动就立即 break，什么都录不到。
        shouldStopRecording.set(false)
        speechEndedAt = 0L
        val modelDir = ModelManager.findAsrModelDir(this)
        if (modelDir == null) {
            ttsEngine.speak(getString(R.string.tts_model_unavailable))
            resumeWake()
            return
        }
        recognitionJob = scope.launch(Dispatchers.IO) {
            // 使用自由听写模式（不限制 Grammar 词表）
            val recognizer = SpeechRecognizer.create(modelDir)
            val minBuf = AudioRecord.getMinBufferSize(
                SpeechRecognizer.SAMPLE_RATE.toInt(),
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            if (minBuf <= 0) {
                recognizer.release()
                withContext(Dispatchers.Main) { resumeWake() }
                return@launch
            }
            val record = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SpeechRecognizer.SAMPLE_RATE.toInt(),
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuf * 8, 256_000)  // minBuf*8，最小256KB（约8秒缓冲）
            )

            // 初始化音频降噪（系统降噪 + 高通滤波器）
            val recNoiseReducer = AudioNoiseReducer.create(record)

            currentState = State.LISTENING
            lastPartialText = ""
            record.startRecording()
            android.util.Log.d("VoiceService", "开始录音识别")
            sendRecognitionLog("🎙️ 开始录音识别")

            // ===== 环境噪音采样，动态设定静音阈值 =====
            val warmupSamples = (NOISE_WARMUP_MS * SpeechRecognizer.SAMPLE_RATE / 1000).toInt()
            val warmupBuf = ShortArray(warmupSamples)
            try {
                record.read(warmupBuf, 0, warmupBuf.size)
            } catch (_: Exception) {
            }

            val noiseSampleCount = (NOISE_SAMPLE_MS * SpeechRecognizer.SAMPLE_RATE / 1000).toInt()
            val noiseBuf = ShortArray(noiseSampleCount)
            val noiseRead = try {
                record.read(noiseBuf, 0, noiseSampleCount)
            } catch (_: Exception) {
                -1
            }
            if (noiseRead > 0) {
                recNoiseReducer.process(noiseBuf, noiseRead, enableRnNoise = false)
                applyGain(noiseBuf, noiseRead)
            }
            val ambientRms = if (noiseRead > 0) {
                SpeechRecognizer.calculateRms(noiseBuf, noiseRead)
            } else {
                SILENCE_RMS_MIN
            }
            val adaptiveSilenceThreshold = (ambientRms * NOISE_MULTIPLIER)
                .coerceIn(SILENCE_RMS_MIN, SILENCE_RMS_MAX)
            android.util.Log.d("VoiceService",
                "环境噪音 RMS=${ambientRms.toInt()}（含增益）, 自适应静音阈值=${adaptiveSilenceThreshold.toInt()}")
                sendRecognitionLog("📊 环境噪音RMS=${ambientRms.toInt()}, 阈值=${adaptiveSilenceThreshold.toInt()}")
            // =========================================

            // ★★★ 方案3：生产者-消费者模式，录音线程和识别线程分离 ★★★
            // 录音线程（生产者）：只做 record.read() + 入队，不做任何处理，避免阻塞导致丢帧
            // 识别线程（消费者，当前协程）：从队列取音频 + 所有处理（降噪/增益/Vosk识别/UI更新/端点检测）
            val audioQueue = java.util.concurrent.LinkedBlockingQueue<AudioFrame>(300)  // 约10秒缓冲
            val recordingFinished = java.util.concurrent.atomic.AtomicBoolean(false)

            // ★ 录音线程（生产者）：只做录音和入队，极轻量，不会阻塞
            val recordingJob = scope.launch(Dispatchers.IO) {
                try {
                    val recordBuf = ShortArray(512)
                    while (!shouldStopRecording.get()) {
                        val n = record.read(recordBuf, 0, recordBuf.size, AudioRecord.READ_BLOCKING)
                        if (n <= 0) {
                            Thread.sleep(10)
                            continue
                        }
                        // 复制一份数据（recordBuf 会被复用）
                        val dataCopy = ShortArray(n)
                        System.arraycopy(recordBuf, 0, dataCopy, 0, n)
                        // 用 offer() + 100ms 超时，避免队列满时永久阻塞（理论死锁风险）
                        // 队列容量300帧（约10秒），实际很难满；如果真满了，丢弃这一帧并打日志
                        if (!audioQueue.offer(AudioFrame(dataCopy, n), 100, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                            android.util.Log.w("VoiceService", "音频队列满，丢弃一帧（${n} samples）")
                        }
                    }
                } catch (e: Exception) {
                    android.util.Log.w("VoiceService", "录音线程异常: ${e.message}")
                } finally {
                    recordingFinished.set(true)
                    // 放入结束帧，唤醒识别线程
                    audioQueue.offer(AudioFrame(ShortArray(0), 0))
                }
            }

            // ★ 识别循环（消费者，当前协程）：做所有处理
            val shortBuf = ShortArray(512)
            val byteBuf = ByteArray(1024)
            val startMs = SystemClock.elapsedRealtime()
            var lastPartial = ""
            var lastPartialUpdateMs = 0L
            var silenceDuration = 0L
            var hasSpeechStarted = false
            var speechFrameCount = 0
            var finalText = ""
            val audioBuffer = java.io.ByteArrayOutputStream()

            try {
                loop@ while (true) {
                    // 从队列取音频帧（阻塞等待，最多等100ms，避免队列空时卡死）
                    val frame = audioQueue.poll(100, java.util.concurrent.TimeUnit.MILLISECONDS)
                    if (frame == null) {
                        // 队列空，检查是否录音已结束
                        if (recordingFinished.get() && audioQueue.isEmpty()) {
                            break
                        }
                        continue
                    }
                    if (frame.length <= 0) {
                        // 结束帧
                        break
                    }
                    val n = frame.length
                    // 把数据复制到 shortBuf（后续处理会原地修改）
                    System.arraycopy(frame.data, 0, shortBuf, 0, n)

                    // 应用降噪处理（高通滤波 + RNNoise）
                    recNoiseReducer.process(shortBuf, n, enableRnNoise = false)

                    // 保存原始音频用于诊断（不加增益）
                    val baiduBytes = ByteArray(n * 2)
                    shortsToBytes(shortBuf, n, baiduBytes)
                    audioBuffer.write(baiduBytes)

                    // 给 Vosk 离线识别的音频：应用 asrGain
                    applyGain(shortBuf, n)

                    // 计算 RMS 能量，判断是否静音
                    val rms = SpeechRecognizer.calculateRms(shortBuf, n)
                    currentRms = rms.toInt()
                    if (rms.toInt() > peakRms) {
                        peakRms = rms.toInt()
                    }
                    val isSilence = rms < adaptiveSilenceThreshold

                    // 检测到连续3帧有效语音后，标记用户已开口
                    if (!hasSpeechStarted) {
                        if (!isSilence) {
                            speechFrameCount++
                            if (speechFrameCount >= 3) {
                                hasSpeechStarted = true
                                hasSpeechStartedThisSession = true
                                android.util.Log.d("VoiceService", "检测到用户开口 (连续${speechFrameCount}帧非静音, RMS=${rms.toInt()})")
                                sendRecognitionLog("🗣️ 检测到开口 (连续${speechFrameCount}帧, RMS=${rms.toInt()})")
                            }
                        } else {
                            speechFrameCount = 0
                        }
                    }

                    // 转换为字节并喂给识别器
                    shortsToBytes(shortBuf, n, byteBuf)
                    val partial = recognizer.feed(byteBuf, n * 2)

                    // 更新 partial 显示（节流：每100ms最多更新一次）
                    if (!partial.isNullOrEmpty() && partial != lastPartial) {
                        lastPartial = partial
                        lastPartialText = partial
                        android.util.Log.d("VoiceService", "识别中: '$partial' (RMS=${rms.toInt()}, 静音=$isSilence)")
                        val nowMs = SystemClock.elapsedRealtime()
                        if (nowMs - lastPartialUpdateMs >= 100) {
                            lastPartialUpdateMs = nowMs
                            withContext(Dispatchers.Main) {
                                FloatViewService.updateSubtitle("💬 $partial")
                            }
                        }
                    }

                    // 基于 RMS 的端点检测
                    val recordDuration = SystemClock.elapsedRealtime() - startMs
                    if (hasSpeechStarted && isSilence) {
                        silenceDuration += (n * 1000L / SpeechRecognizer.SAMPLE_RATE.toInt())
                        val dynamicSilenceMs = if (lastPartial.isNotEmpty()) 3000L else 2000L
                        if (silenceDuration >= dynamicSilenceMs && recordDuration >= MIN_RECORD_MS) {
                            android.util.Log.d("VoiceService",
                                "连续静音${silenceDuration}ms，确认用户说完了，结束录音 (RMS=${rms.toInt()}, 阈值=${adaptiveSilenceThreshold.toInt()})")
                                sendRecognitionLog("⏹️ 结束录音 (静音${silenceDuration}ms, RMS=${rms.toInt()})")
                            shouldStopRecording.set(true)
                            break@loop
                        }
                    } else if (!isSilence) {
                        silenceDuration = 0
                    }

                    // 超时保护
                    val timeoutLimit = if (hasSpeechStarted) MAX_RECORD_MS else WAIT_SPEECH_TIMEOUT_MS
                    if (recordDuration > timeoutLimit) {
                        android.util.Log.d("VoiceService",
                            "录音超时（${if (hasSpeechStarted) "已开口" else "未检测到语音"}，${recordDuration}ms）")
                        shouldStopRecording.set(true)
                        break@loop
                    }
                }

                // 等待录音线程结束（最多等2秒，超时就 cancel，避免永久阻塞）
                shouldStopRecording.set(true)
                try {
                    // 主动 stop AudioRecord，中断阻塞的 read() 调用
                    try { record.stop() } catch (_: Exception) {}
                    // Job.join() 是无参的，用 withTimeoutOrNull 实现带超时的等待
                    withTimeoutOrNull(2000) { recordingJob.join() }
                } catch (_: Exception) {}
                if (recordingJob.isActive) {
                    recordingJob.cancel()
                }

                // 处理队列里剩余的音频帧（如果有）
                while (true) {
                    val frame = audioQueue.poll() ?: break
                    if (frame.length <= 0) break
                    val n = frame.length
                    System.arraycopy(frame.data, 0, shortBuf, 0, n)
                    recNoiseReducer.process(shortBuf, n, enableRnNoise = false)
                    val baiduBytes = ByteArray(n * 2)
                    shortsToBytes(shortBuf, n, baiduBytes)
                    audioBuffer.write(baiduBytes)
                    applyGain(shortBuf, n)
                    shortsToBytes(shortBuf, n, byteBuf)
                    recognizer.feed(byteBuf, n * 2)
                }

                finalText = recognizer.finish()
                android.util.Log.d("VoiceService", "Vosk 识别文本: '$finalText'")

                // 保存本次录音为 WAV 文件
                try {
                    val saveLabel = finalText.ifEmpty { "未识别" }
                    AudioSaver.saveRecording(audioBuffer.toByteArray(), saveLabel)
                } catch (e: Exception) {
                    android.util.Log.w("VoiceService", "保存录音失败: ${e.message}")
                }

                android.util.Log.d("VoiceService", "最终识别文本（Vosk离线）: '$finalText'")
            } finally {
                try { record.stop() } catch (_: Exception) {}
                record.release()
                recNoiseReducer.release()
                try { recognizer.release() } catch (_: Exception) {}
                withContext(Dispatchers.Main) { recognitionJob = null }
            }
            withContext(Dispatchers.Main) { handleText(finalText) }
        }
    }

    /**
     * 对 PCM 音频数据应用增益放大（识别阶段专用）
     * 使用独立的 asrGain（默认 1.5x），比唤醒增益（默认 4.5x）保守，
     * 避免近场说话时波形削顶失真，反而降低 Vosk 识别率。
     */
    private fun applyGain(buffer: ShortArray, length: Int) {
        val gain = WakeWordEngine.getAsrGain()
        if (gain <= 1.0f) return  // 增益为 1.0 时不需要处理
        for (i in 0 until length) {
            val amplified = buffer[i] * gain
            // 防止溢出，截断到 short 范围
            buffer[i] = when {
                amplified > Short.MAX_VALUE -> Short.MAX_VALUE
                amplified < Short.MIN_VALUE -> Short.MIN_VALUE
                else -> amplified.toInt().toShort()
            }
        }
    }

    /**
     * 对 PCM 音频数据应用指定增益值（用于百度在线识别）
     * @param gain 增益倍数，如 2.5f 表示放大 2.5 倍
     */
    private fun applyGainWithValue(buffer: ShortArray, length: Int, gain: Float) {
        if (gain <= 1.0f) return  // 增益为 1.0 时不需要处理
        for (i in 0 until length) {
            val amplified = buffer[i] * gain
            // 防止溢出，截断到 short 范围
            buffer[i] = when {
                amplified > Short.MAX_VALUE -> Short.MAX_VALUE
                amplified < Short.MIN_VALUE -> Short.MIN_VALUE
                else -> amplified.toInt().toShort()
            }
        }
    }

    // 网络相关逻辑已移到 NetworkMonitor

    // ---------- 文本处理 ----------
    /**
     * 同音字/常见识别错误纠正
     * 把语音识别中常见的同音字错误自动纠正为正确的词
     * 例如："牛围村" -> "牛圩村"（圩和围同音 wéi）
     */
    private fun correctHomophones(text: String): String {
        // 先去除空格（Vosk 识别结果中词之间有空格，如"导航 到 牛 为 春"）
        var result = text.replace(" ", "")
        // 同音字纠正映射表：识别错的词 -> 正确的词
        val corrections = mapOf(
            // 牛圩村的各种同音字/识别错误变体
            "牛围村" to "牛圩村",
            "牛为村" to "牛圩村",
            "牛韦村" to "牛圩村",
            "牛围春" to "牛圩村",
            "牛为春" to "牛圩村",
            "牛韦春" to "牛圩村",
            "牛围存" to "牛圩村",
            "牛为存" to "牛圩村",
            "牛韦存" to "牛圩村",
            // 其他常见同音字纠正
            "娅" to "亚",
            "米娅" to "米亚",
            // 空调控制："调"常被识别成"条"（发音相同 tiáo）
            "空调条到" to "空调调到",
            "空调条的" to "空调调到",  // "到"被识别成"的"的情况
            "条到" to "调到",
            "条至" to "调至"
            // 可以在这里继续添加其他同音字纠正，例如：
            // "七里香" to "七里香",  // 如果识别成其他同音字
            // "万达广场" to "万达广场",
        )
        for ((wrong, correct) in corrections) {
            if (result.contains(wrong)) {
                result = result.replace(wrong, correct)
            }
        }
        return result
    }

    private fun handleText(text: String) {
        currentState = State.PROCESSING

        // 记录用户说的话到文件日志（方便用户在日志查看页面查看识别是否完整）
        LogUtils.i("VoiceService", "识别文本: '$text'")
        // 同音字/常见错误纠正：把识别错的词自动纠正
        val correctedText = correctHomophones(text)
        if (correctedText != text) {
            LogUtils.i("VoiceService", "同音字纠正: '$text' -> '$correctedText'")
        }
        lastRecognizedText = correctedText
        FloatViewService.updateSubtitle("👉 $correctedText")
        scheduleSubtitleClear(15000)

        // 发送广播，将识别文本显示到设置页的运行日志中（不管有没有匹配到意图）
        if (correctedText.isNotBlank()) {
            sendRecognitionLog("🎤 识别: $correctedText")
        }

        if (text.isBlank()) {
            // 没听清：恢复系统音量到原始值，TTS 播报时请求音频焦点，音乐自动降低
            // TTS结束后保持原始音量值，不降到0
            restoreMediaVolume()

            if (!hasSpeechStartedThisSession) {
                // 用户根本没开口（8秒超时）：直接退出到待机状态，不提示"没有听清"
                LogUtils.i("VoiceService", "唤醒后8秒未检测到有效语音，自动退出到待机状态")
                FloatViewService.updateSubtitle("")
                currentState = State.IDLE
                                resumeWake()
                return
            }

            // 用户开口了但是没识别到内容（可能是噪音或说话太小）：提示"没有听清"，可以重试
            noSpeechRetryCount++
            LogUtils.w("VoiceService", "用户开口了但未识别到有效内容（第${noSpeechRetryCount}次）")

            if (noSpeechRetryCount >= MAX_NO_SPEECH_RETRY) {
                // 连续2次开口但没识别到，自动退出
                LogUtils.i("VoiceService", "连续${MAX_NO_SPEECH_RETRY}次未识别到有效内容，自动退出到待机状态")
                FloatViewService.updateSubtitle("")
                currentState = State.IDLE
                                resumeWake()
                return
            }

            FloatViewService.updateSubtitle(getString(R.string.subtitle_not_heard))
            // ★ 如果本次是"在线失败回退离线"的场景，离线也没听清时直接恢复唤醒监听，不再重试
            // 防止无限循环：在线失败→回退离线→离线没听清→又startRecognition()→又选在线→又失败...
            if (isFallbackFromOnline) {
                android.util.Log.d("VoiceService", "在线失败回退离线后仍未识别到内容，直接恢复唤醒监听（避免无限循环）")
                isFallbackFromOnline = false
                currentState = State.IDLE
                resumeWake()
                return
            }
            // 设置标志位：TTS说完后直接重新监听，不需要唤醒词（和没听懂分支一致，避免固定3秒延迟竞态）
            isRetryListening = true
            ttsEngine.speak(getString(R.string.tts_not_heard))
            if (!ttsEngine.isReady) {
                // TTS不可用时，直接重新监听
                isRetryListening = false
                LogUtils.d("VoiceService", "TTS不可用，直接重新开始录音识别")
                mainHandler.postDelayed({
                    startRecognition()
                }, 300)
            } else {
                // ★ 超时兜底：TTS播报后5秒内如果没收到 onSpeakDone 回调，自动恢复唤醒监听
                // 防止TTS引擎内部错误导致 onSpeakDone 不回调，服务卡在 PROCESSING 状态
                mainHandler.postDelayed({
                    if (isRetryListening) {
                        android.util.Log.w("VoiceService", "TTS播报超时（5秒未收到onSpeakDone），自动恢复唤醒监听")
                        isRetryListening = false
                        currentState = State.IDLE
                        resumeWake()
                    }
                }, 5000)
            }
            return
        }

        val rawIntent = intentParser.parse(correctedText)
        // 导航意图的地名同音字纠正：如果是 nav.to，对 dest 参数做拼音模糊匹配
        val intent = if (rawIntent != null && rawIntent.action == "nav.to") {
            val dest = rawIntent.params["dest"]
            if (dest != null) {
                val matchedDest = placeMatcher.match(dest)
                if (matchedDest != null && matchedDest != dest) {
                    LogUtils.d("VoiceService", "地名匹配: '$dest' -> '$matchedDest'")
                    // 创建新的 VoiceIntent，替换 dest 参数
                    val newParams = rawIntent.params.toMutableMap()
                    newParams["dest"] = matchedDest
                    VoiceIntent(rawIntent.id, rawIntent.action, newParams)
                } else {
                    rawIntent
                }
            } else {
                rawIntent
            }
        } else {
            rawIntent
        }
        if (intent == null) {
            // 没听懂：恢复系统音量到原始值，TTS 播报时请求音频焦点，音乐自动降低
            // TTS结束后保持原始音量值，不降到0
            restoreMediaVolume()
            LogUtils.w("VoiceService", "未匹配到意图: '$correctedText'")
            // 发送广播，将没听懂的结果显示到设置页的运行日志中
            sendRecognitionLog("❌ 未匹配: $correctedText")
            FloatViewService.updateSubtitle(getString(R.string.subtitle_not_understood))
            // ★ 如果本次是"在线失败回退离线"的场景，离线也没听懂时直接恢复唤醒监听，不再重试
            // 防止无限循环：在线失败→回退离线→离线没听懂→又startRecognition()→又选在线→又失败...
            if (isFallbackFromOnline) {
                android.util.Log.d("VoiceService", "在线失败回退离线后仍未匹配到意图，直接恢复唤醒监听（避免无限循环）")
                isFallbackFromOnline = false
                currentState = State.IDLE
                resumeWake()
                return
            }
            // 设置标志位：TTS说完后直接重新监听，不需要唤醒词
            isRetryListening = true
            ttsEngine.speak(getString(R.string.tts_not_understood))
            if (!ttsEngine.isReady) {
                // TTS不可用时，直接重新监听
                isRetryListening = false
                android.util.Log.d("VoiceService", "TTS不可用，直接重新开始录音识别")
                mainHandler.postDelayed({
                    startRecognition()
                }, 300)
            }
            return
        }

        // 正常识别完成：恢复媒体音量到原始值，让 TTS 播报结果能听到
        restoreMediaVolume()
        // 成功识别，重置连续没说话的计数
        noSpeechRetryCount = 0
        LogUtils.i("VoiceService", "匹配意图: ${intent.action}, 参数: ${intent.params}")
        // 发送广播，将意图匹配结果显示到设置页的运行日志中
        sendRecognitionLog("✅ 匹配: ${intent.action} → ${intent.params}")
        if (intent.action == "app.cancel") {
            currentState = State.IDLE
            FloatViewService.updateSubtitle(getString(R.string.subtitle_cancelled))
            resumeWake()
            return
        }

        // 音乐搜索（已移除无障碍自动搜索，只打开播放器并提示手动搜索）
        if (intent.action == "media.search_play") {
            val result = skillExecutor.execute(intent)
            FloatViewService.updateSubtitle("✅ ${result.spoken}")
            ttsEngine.speak(result.spoken)
            if (!ttsEngine.isReady) {
                currentState = State.IDLE
                resumeWake()
            }
            return
        }

        // 天气查询（需要网络请求，在后台线程执行）
        if (intent.action == "ask.weather") {
            // 从意图参数中提取城市名（用户说"北京天气"时）
            // 支持"明天蚌埠天气"这种说法：提取"蚌埠"作为城市名
            val rawCity = intent.params["city"]
            val specifiedCity = when {
                rawCity.isNullOrBlank() -> null
                skillExecutor.isOnlyTimeWord(rawCity) -> null  // 只有时间词，没有城市名
                else -> skillExecutor.extractCity(rawCity)  // 去掉时间词，提取城市名
            }

            // 先播报"正在查询"，让用户知道正在处理
            val searchingText = if (!specifiedCity.isNullOrBlank()) {
                "正在查询${specifiedCity}天气，请稍候"
            } else {
                "正在查询当前位置天气，请稍候"
            }
            FloatViewService.updateSubtitle("🔍 $searchingText")
            ttsEngine.speak(searchingText)

            // 在后台线程执行网络请求
            Thread {
                try {
                    // 从识别文本中提取时间词（今天/明天/后天）
                    val timeIndex = skillExecutor.getTimeIndex(correctedText)
                    val weatherResult = skillExecutor.queryWeather(specifiedCity, timeIndex)

                    if (weatherResult == null) {
                        // 定位失败：提示用户说城市名，引导用户重新说
                        LogUtils.w("VoiceService", "天气查询定位失败，提示用户说城市名")
                        mainHandler.post {
                            val guideText = "无法确定当前位置，您可以说北京天气、上海天气来查询指定城市的天气"
                            FloatViewService.updateSubtitle("📍 $guideText")
                            ttsEngine.speak(guideText)
                            // TTS说完后重新进入聆听状态，让用户说城市名
                            isRetryListening = true
                            if (!ttsEngine.isReady) {
                                isRetryListening = false
                                mainHandler.postDelayed({ startRecognition() }, 300)
                            }
                        }
                    } else {
                        // 查询成功：播报天气结果
                        LogUtils.i("VoiceService", "天气查询结果: $weatherResult")
                        mainHandler.post {
                            FloatViewService.updateSubtitle("🌤️ $weatherResult")
                            ttsEngine.speak(weatherResult)
                            lastIntentResult = "天气: $weatherResult"
                            if (!ttsEngine.isReady) {
                                currentState = State.IDLE
                                resumeWake()
                            }
                        }
                    }
                } catch (e: Exception) {
                    LogUtils.w("VoiceService", "天气查询异常: ${e.message}")
                    mainHandler.post {
                        val errorText = "天气查询失败，请检查网络连接"
                        FloatViewService.updateSubtitle("❌ $errorText")
                        ttsEngine.speak(errorText)
                        currentState = State.IDLE
                        resumeWake()
                    }
                }
            }.start()
            return
        }

        // ★ 导航意图特殊处理：先 TTS 播报，播报完再拉起导航
        // 原因：如果先拉起导航再 TTS 播报，导航的语音和我们的 TTS 会同时响，声音重叠
        if (intent.action == "nav.to" || intent.action == "nav.home" || intent.action == "nav.company" || intent.action == "nav.nearby") {
            val spoken = when (intent.action) {
                "nav.home" -> "正在为您导航回家"
                "nav.company" -> "正在为您导航去公司"
                "nav.nearby" -> {
                    val keyword = intent.params["keyword"] ?: ""
                    if (keyword.isNotBlank()) "正在为您搜索附近的$keyword" else "正在为您搜索附近"
                }
                else -> {
                    val dest = intent.params["dest"] ?: ""
                    if (dest.isNotBlank()) "正在为您导航到$dest" else "正在为您导航"
                }
            }
            LogUtils.i("VoiceService", "导航意图：先 TTS 播报'$spoken'，播报完再拉起导航（避免声音重叠）")
            pendingNavIntent = intent
            FloatViewService.updateSubtitle("🧭 $spoken")
            ttsEngine.speak(spoken)
            if (!ttsEngine.isReady) {
                // TTS 不可用，直接拉起高德
                LogUtils.w("VoiceService", "TTS 不可用，直接拉起高德")
                pendingNavIntent?.let { navIntent ->
                    skillExecutor.execute(navIntent)
                    pendingNavIntent = null
                }
                currentState = State.IDLE
                resumeWake()
            }
            return
        }

        val result = skillExecutor.execute(intent)
        LogUtils.i("VoiceService", "执行结果: handled=${result.handled}, spoken='${result.spoken}'")
        lastIntentResult = if (result.handled) getString(R.string.result_executed, result.spoken) else getString(R.string.result_unmatched, result.spoken)
        FloatViewService.updateSubtitle("✅ ${result.spoken}")

        if (result.spoken.isBlank()) {
            currentState = State.IDLE
            resumeWake()
            return
        }

        // 如果拉起了支持语音选择的导航，标记待暂停（TTS播完后再暂停，避免TTS播报时麦克风已释放）
        if (result.needsMicPause) {
            pendingExternalNavPause = true
            LogUtils.i("VoiceService", "导航应用支持语音选择，TTS播完后将暂停唤醒监听给导航让麦")
        }

        ttsEngine.speak(result.spoken)
        if (!ttsEngine.isReady) {
            currentState = State.IDLE
            if (pendingExternalNavPause) {
                pendingExternalNavPause = false
                pauseWakeForExternalNav()
            } else {
                resumeWake()
            }
        }
    }


    /**
     * 发送识别日志，同时写入文件和发送广播
     * - 写入 LogUtils 文件：LogViewerActivity（查看日志页面）可以查看和导出
     * - 发送广播：MainActivity 实时显示到设置页的运行日志
     */
    private fun sendRecognitionLog(message: String) {
        // 写入文件日志（查看日志页面可以查看和导出到U盘）
        LogUtils.i("Recognition", message)
        // 发送广播（设置页实时显示）
        try {
            val intent = Intent(ACTION_RECOGNITION_LOG).apply {
                setPackage(packageName)
                putExtra(EXTRA_LOG_MESSAGE, message)
            }
            sendBroadcast(intent)
        } catch (e: Exception) {
            LogUtils.w("VoiceService", "发送识别日志广播失败: ${e.message}")
        }
    }


    override fun onDestroy() {
        instance = null
        currentState = State.IDLE
        recognitionJob?.cancel()
        scope.cancel()
        // 注意顺序：先停止唤醒线程（会等待线程结束），再关闭 ONNX session
        // 防止线程还在访问已关闭的 session 导致崩溃
        stopWakeListening()
        wakeWordEngine.close()
        // 释放缓存的 Vosk Model，避免一直占内存（小模型约120MB，大模型可能1.5GB）
        SpeechRecognizer.releaseCachedModel()
        // 注销电话状态监听
        phoneStateMonitor.stop()
        // 注销网络变化监听
        networkMonitor.stop()
        // 释放百度语音识别引擎（EventManager + factory），避免服务反复启停时 SDK 资源累积泄漏
        // 注意：只释放引擎，保留配置（appId/apiKey/secretKey），下次 init() 直接复用
        baiduAsrManager.releaseEngine()
        ttsEngine.shutdown()
        // 释放提示音播放器
        toneGenerator?.release()
        toneGenerator = null
        FloatViewService.updateSubtitle("")
        mainHandler.removeCallbacks(clearSubtitleRunnable)
        // 取消外部导航暂停定时器
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun scheduleSubtitleClear(delayMs: Long) {
        mainHandler.removeCallbacks(clearSubtitleRunnable)
        mainHandler.postDelayed(clearSubtitleRunnable, delayMs)
    }

    private val clearSubtitleRunnable = Runnable {
        FloatViewService.updateSubtitle("")
    }

    private fun shortsToBytes(shortData: ShortArray, count: Int, out: ByteArray) {
        for (i in 0 until count) {
            val s = shortData[i].toInt()
            out[i * 2] = (s and 0xFF).toByte()
            out[i * 2 + 1] = ((s shr 8) and 0xFF).toByte()
        }
    }
}