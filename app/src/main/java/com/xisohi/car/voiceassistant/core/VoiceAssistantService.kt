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
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import com.xisohi.car.voiceassistant.R
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
 * Porcupine 的采集，再开识别采集，避免两个 AudioRecord 抢麦克风。
 */
class VoiceAssistantService : Service() {

    enum class State { IDLE, LISTENING, PROCESSING, SPEAKING }

    companion object {
        const val ACTION_START = "com.xisohi.car.voiceassistant.action.START"
        const val ACTION_STOP = "com.xisohi.car.voiceassistant.action.STOP"
        private const val CHANNEL_ID = "voice_assistant"
        private const val NOTIF_ID = 1
        private const val MAX_RECORD_MS = 10_000L

        @Volatile
        private var instance: VoiceAssistantService? = null

        @Volatile
        var currentState: State = State.IDLE
            private set

        val isRunning: Boolean get() = instance != null

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
                    resumeWake()
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
        }
        startForegroundCompat()
        currentState = State.IDLE
        resumeWake()
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
            // AccessKey 未配置或唤醒词缺失：UI 会有明确提示
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
        val grammar = intentParser.grammarPhrases()
        recognitionJob = scope.launch(Dispatchers.IO) {
            val recognizer = SpeechRecognizer.create(modelDir, grammar)
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
            val shortBuf = ShortArray(512)
            val byteBuf = ByteArray(1024)
            val startMs = SystemClock.elapsedRealtime()

            try {
                loop@ while (true) {
                    val n = record.read(shortBuf, 0, shortBuf.size)
                    if (n <= 0) continue
                    shortsToBytes(shortBuf, n, byteBuf)
                    recognizer.feed(byteBuf, n * 2)
                    if (recognizer.isEndpoint()) break@loop
                    if (SystemClock.elapsedRealtime() - startMs > MAX_RECORD_MS) break@loop
                }
                val finalText = recognizer.finish()
                withContext(Dispatchers.Main) { handleText(finalText) }
            } finally {
                try {
                    record.stop()
                } catch (_: Exception) {
                }
                record.release()
                recognizer.release()
                recognitionJob = null
            }
        }
    }

    private fun handleText(text: String) {
        currentState = State.PROCESSING
        if (text.isBlank()) {
            ttsEngine.speak("没有听清，请再说一遍")
            return
        }
        val intent = intentParser.parse(text)
        if (intent == null) {
            ttsEngine.speak("没听懂，可以试试说：把音量调到五十、播放音乐、导航去机场")
            return
        }
        if (intent.action == "app.cancel") {
            currentState = State.IDLE
            resumeWake()
            return
        }
        val result = skillExecutor.execute(intent)
        ttsEngine.speak(result.spoken)
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
