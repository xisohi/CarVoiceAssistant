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
import com.xisohi.car.voiceassistant.core.autoinput.MusicFreeInputHandler
import com.xisohi.car.voiceassistant.core.autoinput.SongInfo
import com.xisohi.car.voiceassistant.download.ModelManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 常驻语音助手前台服务。
 *
 * 状态机：
 *   IDLE（唤醒监听中）→ 唤醒 → LISTENING（采集+识别）
 *   → PROCESSING（解析+执行）→ SPEAKING（TTS 播报）→ IDLE
 *
 * 播报期间暂停唤醒监听（防回声误触发）；唤醒后会先停掉
 * 唤醒引擎的采集，再开识别采集，避免两个 AudioRecord 抢麦克风。
 */
class VoiceAssistantService : Service() {

    enum class State { IDLE, LISTENING, PROCESSING, SPEAKING }

    companion object {
        const val ACTION_START = "com.xisohi.car.voiceassistant.action.START"
        const val ACTION_STOP = "com.xisohi.car.voiceassistant.action.STOP"
        const val ACTION_WAKE_TRIGGER = "com.xisohi.car.voiceassistant.action.WAKE_TRIGGER"
        private const val CHANNEL_ID = "voice_assistant"
        private const val NOTIF_ID = 1
        private const val MAX_RECORD_MS = 10_000L

        @Volatile
        private var instance: VoiceAssistantService? = null

        @Volatile
        var currentState: State = State.IDLE
            private set

        val isRunning: Boolean get() = instance != null

        // ---------- 多轮对话状态 ----------
        /** 当前等待选择的搜索结果列表（音乐搜索后进入选择状态） */
        @Volatile
        private var pendingSongResults: List<SongInfo>? = null

        /** 是否在等待用户选择歌曲（多轮对话状态） */
        @Volatile
        var isWaitingForSongSelection: Boolean = false
            private set

        fun start(context: Context) {
            val intent = Intent(context, VoiceAssistantService::class.java)
                .setAction(ACTION_START)
            if (Build.VERSION.SDK_INT >= 26) {
                context.startForegroundService(intent)
            } else {
                // API 24/25 无 startForegroundService
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
    private lateinit var skillExecutor: SkillExecutor
    private lateinit var ttsEngine: TtsEngine

    private var recognitionJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        currentState = State.IDLE
        createChannel()

        wakeWordEngine = WakeWordEngine(this)
        intentParser = IntentParser(this)
        skillExecutor = SkillExecutor(this)
        ttsEngine = TtsEngine(this).apply {
            listener = object : TtsEngine.Listener {
                override fun onSpeakStart() {
                    currentState = State.SPEAKING
                }

                override fun onSpeakDone() {
                    currentState = State.IDLE
                    // 如果在多轮对话选择状态（等待用户选择歌曲），播报完成后启动新的识别，
                    // 而不是恢复唤醒监听，这样用户说"第一首"会被 handleText 正确处理
                    if (isWaitingForSongSelection) {
                        android.util.Log.d("VoiceService", "选择状态下播报完成，停止唤醒并启动新识别")
                        // 必须先停止 WakeWord，释放麦克风，否则 startRecognition 会报 AudioRecord status -38
                        wakeWordEngine.stop()
                        startRecognition()
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
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_WAKE_TRIGGER -> {
                // 悬浮球点击触发唤醒
                if (currentState == State.IDLE) {
                    onWakeWord()
                }
                return START_STICKY
            }
        }
        startForegroundCompat()
        currentState = State.IDLE
        resumeWake()
        // 启动悬浮窗
        try {
            FloatViewService.start(this)
        } catch (_: Exception) {
        }
        return START_STICKY
    }

    private fun startForegroundCompat() {
        val notification = buildNotification("语音助手运行中")
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(
                NOTIF_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    private fun buildNotification(text: String): Notification {
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_mic)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setOngoing(true)
        return builder.build()
    }

    private fun createChannel() {
        // API 26+ 才需要 NotificationChannel（minSdk 24 需保护）
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "语音助手",
                NotificationManager.IMPORTANCE_LOW
            )
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }

    // ---------- 唤醒 ----------

    private fun resumeWake() {
        if (recognitionJob?.isActive == true) return
        val ok = wakeWordEngine.start { _ ->
            onWakeWord()
        }
        if (!ok) {
            currentState = State.IDLE
            // 唤醒模型未就绪（kws/ 目录缺文件）：UI 会有明确提示
        }
    }

    private fun onWakeWord() {
        if (currentState != State.IDLE) return
        // 停掉唤醒采集，避免与识别采集抢麦克风
        wakeWordEngine.stop()
        startRecognition()
    }

    // ---------- 识别 ----------

    private fun startRecognition() {
        val modelDir = ModelManager.findAsrModelDir(this)
        if (modelDir == null) {
            ttsEngine.speak("语音模型不可用，请先在主界面完成初始化")
            resumeWake()
            return
        }
        // TTS 异步初始化，未就绪时仅跳过播报，识别照常进行
        // 不用 grammar 限定：vosk-model-small-cn 基于字，grammar 中的词组大多不在词表中会被忽略，
        // 自由识别 + IntentParser 正则匹配反而更可靠。
        recognitionJob = scope.launch(Dispatchers.IO) {
            val recognizer = SpeechRecognizer.create(modelDir, null)
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
                MediaRecorder.AudioSource.MIC,
                SpeechRecognizer.SAMPLE_RATE.toInt(),
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuf * 2, 16_000)
            )
            currentState = State.LISTENING
            record.startRecording()
            android.util.Log.d("VoiceService", "开始录音识别")
            val shortBuf = ShortArray(512)
            val byteBuf = ByteArray(1024)
            val startMs = SystemClock.elapsedRealtime()
            var lastPartial = ""

            var finalText = ""
            try {
                loop@ while (true) {
                    val n = record.read(shortBuf, 0, shortBuf.size)
                    if (n <= 0) continue
                    shortsToBytes(shortBuf, n, byteBuf)
                    val partial = recognizer.feed(byteBuf, n * 2)
                    if (!partial.isNullOrEmpty() && partial != lastPartial) {
                        lastPartial = partial
                        android.util.Log.d("VoiceService", "识别中: '$partial'")
                    }
                    if (recognizer.isEndpoint()) {
                        android.util.Log.d("VoiceService", "检测到端点，结束录音 (${SystemClock.elapsedRealtime() - startMs}ms)")
                        break@loop
                    }
                    if (SystemClock.elapsedRealtime() - startMs > MAX_RECORD_MS) {
                        android.util.Log.d("VoiceService", "录音超时 (${MAX_RECORD_MS}ms)，结束录音")
                        break@loop
                    }
                }
                finalText = recognizer.finish()
                android.util.Log.d("VoiceService", "最终识别文本: '$finalText'")
            } finally {
                try {
                    record.stop()
                } catch (_: Exception) {
                }
                record.release()
                recognizer.release()
                recognitionJob = null
            }
            // 必须在麦克风释放后再处理结果，否则恢复唤醒时 AudioRecord 抢不到麦克风
            withContext(Dispatchers.Main) { handleText(finalText) }
        }
    }

    private fun handleText(text: String) {
        currentState = State.PROCESSING
        android.util.Log.d("VoiceService", "识别文本: '$text'")
        if (text.isBlank()) {
            ttsEngine.speak("没有听清，请再说一遍")
            return
        }

        // ---------- 多轮对话：等待选择歌曲 ----------
        if (isWaitingForSongSelection) {
            val index = parseSongIndexFromText(text)
            if (index > 0) {
                android.util.Log.d("VoiceService", "多轮对话：选择第 $index 首")
                val result = skillExecutor.selectSong(index.toString())
                android.util.Log.i("VoiceService", "选择结果: handled=${result.handled}, spoken='${result.spoken}'")
                if (result.handled) {
                    // 选择成功，退出多轮对话状态
                    pendingSongResults = null
                    isWaitingForSongSelection = false
                }
                ttsEngine.speak(result.spoken)
                return
            }
            // 用户说了其他内容，取消选择状态，正常处理
            android.util.Log.d("VoiceService", "取消歌曲选择状态")
            pendingSongResults = null
            isWaitingForSongSelection = false
        }

        val intent = intentParser.parse(text)
        if (intent == null) {
            android.util.Log.w("VoiceService", "未匹配到意图: '$text'")
            ttsEngine.speak("没听懂，可以试试说：把音量调到五十、播放音乐、导航去机场")
            return
        }
        android.util.Log.i("VoiceService", "匹配意图: ${intent.action}, 参数: ${intent.params}")
        if (intent.action == "app.cancel") {
            currentState = State.IDLE
            resumeWake()
            return
        }

        // ---------- 音乐搜索：进入多轮对话 ----------
        if (intent.action == "media.search_play") {
            val result = skillExecutor.execute(intent)
            android.util.Log.i("VoiceService", "搜索结果: handled=${result.handled}, spoken='${result.spoken}'")
            ttsEngine.speak(result.spoken)
            if (result.handled) {
                // 异步等待搜索完成，然后播报结果列表
                scope.launch(Dispatchers.IO) {
                    waitForSearchResultsAndPrompt()
                }
            }
            return
        }

        val result = skillExecutor.execute(intent)
        android.util.Log.i("VoiceService", "执行结果: handled=${result.handled}, spoken='${result.spoken}'")

        if (result.spoken.isBlank()) {
            // 没有播报内容，直接恢复
            currentState = State.IDLE
            resumeWake()
            return
        }

        ttsEngine.speak(result.spoken)

// 保险：15秒后如果状态还是 SPEAKING，强制恢复
        mainHandler.removeCallbacksAndMessages(null)
        mainHandler.postDelayed({
            if (currentState == State.SPEAKING) {
                android.util.Log.w("VoiceService", "TTS超时，强制恢复唤醒")
                currentState = State.IDLE
                resumeWake()
            }
        }, 15000)
    }

    /**
     * 异步等待音乐搜索完成，然后播报结果列表，进入多轮对话选择状态。
     */
    private suspend fun waitForSearchResultsAndPrompt() {
        try {
            // 轮询等待搜索完成，最多等 15 秒
            var waited = 0L
            val interval = 500L
            val maxWait = 15000L
            while (waited < maxWait) {
                if (MusicFreeInputHandler.isSearchCompleted()) {
                    break
                }
                kotlinx.coroutines.delay(interval)
                waited += interval
            }
            if (!MusicFreeInputHandler.isSearchCompleted()) {
                android.util.Log.w("VoiceService", "搜索超时")
                withContext(Dispatchers.Main) {
                    ttsEngine.speak("搜索超时，请重试")
                }
                return
            }
            // 搜索完成后再多等 2 秒，确保搜索结果页加载完成
            kotlinx.coroutines.delay(2000)
            // 点击"单曲"分类，切换到歌曲列表视图
            val clickedSingle = MusicFreeInputHandler.clickSingleTab()
            android.util.Log.d("VoiceService", "点击'单曲'分类结果: $clickedSingle")
            // 等待歌曲列表加载
            kotlinx.coroutines.delay(3000)
            // 读取搜索结果（自动跳过音乐源名称行，只识别真正的歌曲）
            val results = MusicFreeInputHandler.getSearchResults()
            // 如果没有结果，尝试不点击分类直接读取
            val finalResults = if (results.isEmpty()) {
                android.util.Log.d("VoiceService", "点击分类后无结果，尝试直接读取")
                MusicFreeInputHandler.getSearchResults()
            } else {
                results
            }
            if (finalResults.isEmpty()) {
                withContext(Dispatchers.Main) {
                    ttsEngine.speak("没有找到相关歌曲")
                }
                return
            }
            // 进入多轮对话选择状态
            pendingSongResults = finalResults
            isWaitingForSongSelection = true
            // 播报结果列表
            val prompt = buildString {
                append("找到${finalResults.size}首，")
                finalResults.take(5).forEachIndexed { index, song ->
                    append("第${index + 1}首，${song.title}")
                    if (song.artist.isNotEmpty()) append("，${song.artist}")
                    append("；")
                }
                if (finalResults.size > 5) append("等${finalResults.size}首。")
                append("请问播放第几首？")
            }
            android.util.Log.d("VoiceService", "搜索结果播报: $prompt")
            android.util.Log.d("VoiceService", "TTS 可用性: isReady=${ttsEngine.isReady}")
            withContext(Dispatchers.Main) {
                // 如果 TTS 不可用，直接启动识别（用户看不到播报，但可以直接说"第一首"）
                if (!ttsEngine.isReady) {
                    android.util.Log.w("VoiceService", "TTS 不可用，跳过播报，直接启动识别")
                    wakeWordEngine.stop()
                    startRecognition()
                } else {
                    ttsEngine.speak(prompt)
                }
            }
        } catch (e: Exception) {
            android.util.Log.w("VoiceService", "等待搜索结果失败: ${e.message}")
        }
    }

    /** 从文本中解析歌曲序号（多轮对话用） */
    private fun parseSongIndexFromText(text: String): Int {
        val clean = text.trim()
        // 纯数字
        clean.toIntOrNull()?.let { if (it in 1..10) return it }
        // "第X首" 格式
        val match = Regex("第([一二三四五六七八九十两\\d]{1,3})首").find(clean)
        if (match != null) {
            val numStr = match.groupValues[1]
            numStr.toIntOrNull()?.let { return it }
            val cnNum = mapOf(
                "一" to 1, "二" to 2, "两" to 2, "三" to 3, "四" to 4,
                "五" to 5, "六" to 6, "七" to 7, "八" to 8, "九" to 9, "十" to 10
            )
            cnNum[numStr]?.let { return it }
        }
        return 0
    }

    // ---------- 生命周期 ----------

    override fun onDestroy() {
        instance = null
        currentState = State.IDLE
        recognitionJob?.cancel()
        scope.cancel()
        try {
            wakeWordEngine.stop()
        } catch (_: Exception) {
        }
        ttsEngine.shutdown()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun shortsToBytes(shortData: ShortArray, count: Int, out: ByteArray) {
        for (i in 0 until count) {
            val s = shortData[i].toInt()
            out[i * 2] = (s and 0xFF).toByte()
            out[i * 2 + 1] = ((s shr 8) and 0xFF).toByte()
        }
    }
}
