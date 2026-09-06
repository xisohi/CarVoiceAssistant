package com.xisohi.car.voiceassistant.core

import android.annotation.SuppressLint
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
import com.xisohi.car.voiceassistant.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 悬浮窗服务：在其他应用上方显示一个小悬浮球。
 * 点击悬浮球 = 唤醒语音助手（等同于说"小爱同学"）。
 * 悬浮球颜色/文字随状态变化：空闲（蓝）/ 聆听中（绿）/ 处理中（橙）。
 * 长按拖动可移动位置。
 */
class FloatViewService : Service() {

    companion object {
        fun start(context: Context) {
            context.startService(Intent(context, FloatViewService::class.java))
        }
        fun stop(context: Context) {
            context.stopService(Intent(context, FloatViewService::class.java))
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
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        floatView = View.inflate(this, R.layout.float_ball, null)
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

        // 点击唤醒，长按拖动
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
                        // 点击 = 唤醒
                        triggerWake()
                    }
                    true
                }
                else -> false
            }
        }

        windowManager.addView(floatView, layoutParams)
        startStateMonitoring()
    }

    /** 触发唤醒：如果语音服务在运行，发送唤醒指令；否则启动服务 */
    private fun triggerWake() {
        try {
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

    /** 监听语音服务状态，更新悬浮球外观 */
    private fun startStateMonitoring() {
        stateJob = scope.launch {
            while (true) {
                updateBallState()
                delay(500)
            }
        }
    }

    private fun updateBallState() {
        val stateText = floatView.findViewById<TextView>(R.id.tvFloatState)
        val icon = floatView.findViewById<ImageView>(R.id.ivFloatIcon)
        when (VoiceAssistantService.currentState) {
            VoiceAssistantService.State.IDLE -> {
                stateText?.text = "语音助手"
                icon?.setColorFilter(getColor(R.color.float_idle))
            }
            VoiceAssistantService.State.LISTENING -> {
                stateText?.text = "聆听中…"
                icon?.setColorFilter(getColor(R.color.float_listening))
            }
            VoiceAssistantService.State.PROCESSING -> {
                stateText?.text = "处理中…"
                icon?.setColorFilter(getColor(R.color.float_processing))
            }
            VoiceAssistantService.State.SPEAKING -> {
                stateText?.text = "播报中…"
                icon?.setColorFilter(getColor(R.color.float_speaking))
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        stateJob?.cancel()
        scope.cancel()
        try {
            windowManager.removeView(floatView)
        } catch (_: Exception) {
        }
    }
}
