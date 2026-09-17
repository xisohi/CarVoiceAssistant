package com.xisohi.car.voiceassistant.core

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.TextView
import androidx.core.app.NotificationCompat
import com.xisohi.car.voiceassistant.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class FloatViewService : Service() {

    companion object {
        @Volatile
        var isRunning = false
            private set

        fun start(context: Context) {
            val intent = Intent(context, FloatViewService::class.java)
            if (Build.VERSION.SDK_INT >= 26) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
        fun stop(context: Context) {
            context.stopService(Intent(context, FloatViewService::class.java))
        }

        // ---------- 字幕控制 ----------
        private var subtitleTextView: TextView? = null

        /** 外部调用更新字幕，自动切换到主线程 */
        fun updateSubtitle(text: String?) {
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                if (!isRunning) return@post
                val tv = subtitleTextView
                if (tv == null) {
                    android.util.Log.w("FloatView", "字幕 TextView 未初始化，无法显示")
                    return@post
                }
                if (text.isNullOrEmpty()) {
                    tv.visibility = View.GONE
                } else {
                    tv.text = text
                    tv.visibility = View.VISIBLE
                }
            }
        }
    }

    private lateinit var windowManager: WindowManager
    private lateinit var floatView: View
    private lateinit var layoutParams: WindowManager.LayoutParams
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var stateJob: Job? = null

    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var isDragging = false

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate() {
        super.onCreate()
        isRunning = true
        android.util.Log.d("FloatView", "onCreate 开始，准备创建悬浮窗...")
        try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "float_view_channel",
                "悬浮窗",
                NotificationManager.IMPORTANCE_LOW
            )
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)

            val notification = NotificationCompat.Builder(this, "float_view_channel")
                .setSmallIcon(R.drawable.ic_stat_mic)
                .setContentTitle("语音助手悬浮窗")
                .setContentText("正在运行")
                .setOngoing(true)
                .build()
            startForeground(2, notification)  // NOTIF_ID=2，和 VoiceAssistantService 的 1 区分
        }
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        // ---------- 1. 创建悬浮球（可拖动） ----------
        floatView = View.inflate(this, R.layout.float_ball, null)
        // 找到悬浮球布局中的实时识别 TextView（显示在悬浮球下方）
        Companion.subtitleTextView = floatView.findViewById(R.id.tvFloatSubtitle)
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )
        layoutParams.gravity = Gravity.TOP or Gravity.START
        layoutParams.x = 50
        layoutParams.y = 300

        floatView.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = layoutParams.x
                    initialY = layoutParams.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isDragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - initialTouchX).toInt()
                    val dy = (event.rawY - initialTouchY).toInt()
                    if (Math.abs(dx) > 10 || Math.abs(dy) > 10) {
                        isDragging = true
                        layoutParams.x = initialX + dx
                        layoutParams.y = initialY + dy
                        windowManager.updateViewLayout(floatView, layoutParams)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!isDragging) {
                        triggerWake()
                    }
                    true
                }
                else -> false
            }
        }

        windowManager.addView(floatView, layoutParams)
        android.util.Log.d("FloatView", "悬浮窗已添加到窗口，位置: x=${layoutParams.x}, y=${layoutParams.y}")

        startStateMonitoring()
        } catch (e: Exception) {
            android.util.Log.e("FloatView", "创建悬浮窗失败: ${e.message}", e)
            // 即使失败也不要崩溃，停止服务
            stopSelf()
        }
    }

    // ---------- 原有功能 ----------
    private fun triggerWake() {
        try {
            // 如果当前处于外部导航暂停状态，先取消暂停立即恢复；
            // 如果TTS正在播报导航，停止TTS让onSpeakDone()立即执行导航（不取消导航）
            VoiceAssistantService.cancelExternalNavPause()

            val intent = Intent(this, VoiceAssistantService::class.java)
            if (VoiceAssistantService.isRunning) {
                intent.action = VoiceAssistantService.ACTION_WAKE_TRIGGER
                startService(intent)
            } else {
                VoiceAssistantService.start(this)
            }
        } catch (e: Exception) {
            android.util.Log.w("FloatView", "唤醒失败: ${e.message}")
        }
    }

    private fun startStateMonitoring() {
        stateJob = scope.launch {
            while (true) {
                updateBallState()
                delay(500)
            }
        }
    }

    private fun updateBallState() {
        val icon = floatView.findViewById<ImageView>(R.id.ivFloatIcon)
        when (VoiceAssistantService.currentState) {
            VoiceAssistantService.State.IDLE -> {
                icon?.setImageResource(R.drawable.robot_idle)  // 微笑：待机
            }
            VoiceAssistantService.State.LISTENING -> {
                icon?.setImageResource(R.drawable.robot_listen)  // 眨眼：聆听中
            }
            VoiceAssistantService.State.PROCESSING -> {
                icon?.setImageResource(R.drawable.robot_talk)  // 波浪嘴：处理中
            }
            VoiceAssistantService.State.SPEAKING -> {
                icon?.setImageResource(R.drawable.robot_talk)  // 波浪嘴：说话中
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        stateJob?.cancel()
        scope.cancel()

        // 移除悬浮球（包含实时识别 TextView，会一起移除）
        try {
            windowManager.removeView(floatView)
        } catch (_: Exception) {
        }
        Companion.subtitleTextView = null
    }
}