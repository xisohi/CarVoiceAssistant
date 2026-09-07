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
import com.xisohi.car.voiceassistant.core.wakeword.WakeWordEngine  // 使用您指定的包
import com.xisohi.car.voiceassistant.download.ModelManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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

        @Volatile
        private var pendingSongResults: List<SongInfo>? = null

        @Volatile
        var isWaitingForSongSelection: Boolean = false
            private set

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
    private lateinit var skillExecutor: SkillExecutor
    private lateinit var ttsEngine: TtsEngine

    private var recognitionJob: Job? = null

    // 唤醒音频采集线程
    private var wakeAudioThread: WakeAudioThread? = null
    @Volatile
    private var isWakeListening = false

    override fun onCreate() {
        super.onCreate()
        instance = this
        currentState = State.IDLE
        createChannel()

        // 初始化官方 WakeWordEngine（构造函数自动加载 model_info.json）
        wakeWordEngine = WakeWordEngine(this)
        if (!wakeWordEngine.isLoaded) {
            android.util.Log.e("VoiceService", "唤醒引擎加载失败: ${wakeWordEngine.errorMessage}")
        }

        intentParser = IntentParser(this)
        skillExecutor = SkillExecutor(this)
        ttsEngine = TtsEngine(this).apply {
            listener = object : TtsEngine.Listener {
                override fun onSpeakStart() {
                    currentState = State.SPEAKING
                }

                override fun onSpeakDone() {
                    currentState = State.IDLE
                    if (isWaitingForSongSelection) {
                        android.util.Log.d("VoiceService", "选择状态下播报完成，停止唤醒并启动新识别")
                        stopWakeListening()
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
            FloatViewService.start(this)
        } catch (_: Exception) {
        }
        return START_STICKY
    }

    private fun startForegroundCompat() {
        val notification = buildNotification("语音助手运行中")
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
            val channel = NotificationChannel(CHANNEL_ID, "语音助手", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    // ---------- 唤醒监听 ----------
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
        wakeAudioThread?.interrupt()
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
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                channelConfig,
                audioFormat,
                maxOf(minBufSize * 2, sampleRate)
            )
            if (record.state != AudioRecord.STATE_INITIALIZED) {
                android.util.Log.e("WakeAudioThread", "AudioRecord 初始化失败")
                return
            }

            // 官方引擎需要的帧大小（由 engine.audioSamplesNeeded 获取）
            val frameSize = wakeWordEngine.audioSamplesNeeded
            if (frameSize <= 0) {
                android.util.Log.e("WakeAudioThread", "无效的帧大小")
                record.release()
                return
            }

            val audioBuffer = ShortArray(frameSize)
            record.startRecording()
            android.util.Log.d("WakeAudioThread", "开始录音，帧大小=$frameSize")

            try {
                while (isWakeListening && !isInterrupted()) {
                    val read = record.read(audioBuffer, 0, frameSize, AudioRecord.READ_BLOCKING)
                    if (read == frameSize) {
                        val result = wakeWordEngine.process(audioBuffer)
                        if (result != null && result.wakeWord != null && result.probability > 0.5f) {
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
                android.util.Log.d("WakeAudioThread", "录音线程结束")
            }
        }
    }

    // ---------- 唤醒触发 ----------
    private fun onWakeWord() {
        if (currentState != State.IDLE) return
        // 清除旧字幕
        FloatViewService.updateSubtitle("")
        mainHandler.removeCallbacks(clearSubtitleRunnable)
        // 停止唤醒监听
        stopWakeListening()
        // 启动语音识别
        startRecognition()
    }

    // ---------- 语音识别 ----------
    private fun startRecognition() {
        val modelDir = ModelManager.findAsrModelDir(this)
        if (modelDir == null) {
            ttsEngine.speak("语音模型不可用，请先在主界面完成初始化")
            resumeWake()
            return
        }
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
                        withContext(Dispatchers.Main) {
                            FloatViewService.updateSubtitle("💬 $partial")
                        }
                    }
                    if (recognizer.isEndpoint()) {
                        android.util.Log.d("VoiceService", "检测到端点")
                        break@loop
                    }
                    if (SystemClock.elapsedRealtime() - startMs > MAX_RECORD_MS) {
                        android.util.Log.d("VoiceService", "录音超时")
                        break@loop
                    }
                }
                finalText = recognizer.finish()
                android.util.Log.d("VoiceService", "最终识别文本: '$finalText'")
            } finally {
                try { record.stop() } catch (_: Exception) {}
                record.release()
                recognizer.release()
                recognitionJob = null
            }
            withContext(Dispatchers.Main) { handleText(finalText) }
        }
    }

    // ---------- 文本处理 ----------
    private fun handleText(text: String) {
        currentState = State.PROCESSING
        android.util.Log.d("VoiceService", "识别文本: '$text'")
        FloatViewService.updateSubtitle("👉 $text")
        scheduleSubtitleClear(15000)

        if (text.isBlank()) {
            FloatViewService.updateSubtitle("❌ 没有听清")
            ttsEngine.speak("没有听清，请再说一遍")
            mainHandler.postDelayed({
                if (currentState != VoiceAssistantService.State.IDLE) {
                    currentState = State.IDLE
                    resumeWake()
                }
            }, 3000)
            return
        }

        // 多轮对话选择
        if (isWaitingForSongSelection) {
            val index = parseSongIndexFromText(text)
            if (index > 0) {
                android.util.Log.d("VoiceService", "多轮对话：选择第 $index 首")
                val result = skillExecutor.selectSong(index.toString())
                if (result.handled) {
                    pendingSongResults = null
                    isWaitingForSongSelection = false
                }
                FloatViewService.updateSubtitle("✅ ${result.spoken}")
                ttsEngine.speak(result.spoken)
                if (!ttsEngine.isReady) {
                    currentState = State.IDLE
                    resumeWake()
                }
                return
            }
            pendingSongResults = null
            isWaitingForSongSelection = false
        }

        val intent = intentParser.parse(text)
        if (intent == null) {
            android.util.Log.w("VoiceService", "未匹配到意图: '$text'")
            FloatViewService.updateSubtitle("❌ 没听懂")
            ttsEngine.speak("没听懂，可以试试说：把音量调到五十、播放音乐、导航去机场")
            if (!ttsEngine.isReady) {
                currentState = State.IDLE
                resumeWake()
            }
            return
        }
        android.util.Log.i("VoiceService", "匹配意图: ${intent.action}, 参数: ${intent.params}")
        if (intent.action == "app.cancel") {
            currentState = State.IDLE
            FloatViewService.updateSubtitle("已取消")
            resumeWake()
            return
        }

        // 音乐搜索
        if (intent.action == "media.search_play") {
            val result = skillExecutor.execute(intent)
            FloatViewService.updateSubtitle("⏳ ${result.spoken}")
            ttsEngine.speak(result.spoken)
            if (result.handled) {
                scope.launch(Dispatchers.IO) {
                    waitForSearchResultsAndPrompt()
                }
            } else {
                if (!ttsEngine.isReady) {
                    currentState = State.IDLE
                    resumeWake()
                }
            }
            return
        }

        val result = skillExecutor.execute(intent)
        android.util.Log.i("VoiceService", "执行结果: handled=${result.handled}, spoken='${result.spoken}'")
        FloatViewService.updateSubtitle("✅ ${result.spoken}")

        if (result.spoken.isBlank()) {
            currentState = State.IDLE
            resumeWake()
            return
        }

        ttsEngine.speak(result.spoken)
        if (!ttsEngine.isReady) {
            currentState = State.IDLE
            resumeWake()
        }
    }

    // ---------- 搜索相关 ----------
    private suspend fun waitForSearchResultsAndPrompt() {
        try {
            var waited = 0L
            val interval = 500L
            val maxWait = 15000L
            while (waited < maxWait) {
                if (MusicFreeInputHandler.isSearchCompleted()) break
                kotlinx.coroutines.delay(interval)
                waited += interval
            }
            if (!MusicFreeInputHandler.isSearchCompleted()) {
                withContext(Dispatchers.Main) {
                    FloatViewService.updateSubtitle("❌ 搜索超时")
                    ttsEngine.speak("搜索超时，请重试")
                    if (!ttsEngine.isReady) {
                        currentState = State.IDLE
                        resumeWake()
                    }
                }
                return
            }
            kotlinx.coroutines.delay(2000)
            MusicFreeInputHandler.clickSingleTab()
            kotlinx.coroutines.delay(3000)
            var results = MusicFreeInputHandler.getSearchResults()
            if (results.isEmpty()) {
                results = MusicFreeInputHandler.getSearchResults()
            }
            if (results.isEmpty()) {
                withContext(Dispatchers.Main) {
                    FloatViewService.updateSubtitle("❌ 没有找到相关歌曲")
                    ttsEngine.speak("没有找到相关歌曲")
                    if (!ttsEngine.isReady) {
                        currentState = State.IDLE
                        resumeWake()
                    }
                }
                return
            }
            pendingSongResults = results
            isWaitingForSongSelection = true

            val prompt = buildString {
                append("找到${results.size}首，")
                results.take(5).forEachIndexed { index, song ->
                    append("第${index + 1}首，${song.title}")
                    if (song.artist.isNotEmpty()) append("，${song.artist}")
                    append("；")
                }
                if (results.size > 5) append("等${results.size}首。")
                append("请问播放第几首？")
            }
            withContext(Dispatchers.Main) {
                FloatViewService.updateSubtitle("🎵 找到 ${results.size} 首，请说第几首")
                if (!ttsEngine.isReady) {
                    android.util.Log.w("VoiceService", "TTS不可用，跳过播报，直接启动识别")
                    stopWakeListening()
                    startRecognition()
                } else {
                    ttsEngine.speak(prompt)
                }
            }
        } catch (e: Exception) {
            android.util.Log.w("VoiceService", "等待搜索结果失败: ${e.message}")
            withContext(Dispatchers.Main) {
                FloatViewService.updateSubtitle("❌ 搜索出错")
                currentState = State.IDLE
                resumeWake()
            }
        }
    }

    private fun parseSongIndexFromText(text: String): Int {
        val clean = text.trim()
        clean.toIntOrNull()?.let { if (it in 1..10) return it }
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

    // ---------- 清理 ----------
    override fun onDestroy() {
        instance = null
        currentState = State.IDLE
        recognitionJob?.cancel()
        scope.cancel()
        stopWakeListening()
        wakeWordEngine.close()
        ttsEngine.shutdown()
        FloatViewService.updateSubtitle("")
        mainHandler.removeCallbacks(clearSubtitleRunnable)
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