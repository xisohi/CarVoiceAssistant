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

class FloatViewService : Service() {

    companion object {
        fun start(context: Context) {
            context.startService(Intent(context, FloatViewService::class.java))
        }
        fun stop(context: Context) {
            context.stopService(Intent(context, FloatViewService::class.java))
        }

        // ---------- 字幕控制 ----------
        private var subtitleTextView: TextView? = null

        /** 外部调用更新字幕，自动切换到主线程 */
        fun updateSubtitle(text: String?) {
            android.os.Handler(android.os.Looper.getMainLooper()).post {
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
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        // ---------- 1. 创建悬浮球（可拖动） ----------
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

        // ---------- 2. 创建独立的字幕悬浮窗（固定在顶部中央，透明不可触摸） ----------
        createSubtitleView()

        startStateMonitoring()
    }

    // ---------- 创建独立字幕悬浮窗 ----------
    private fun createSubtitleView() {
        val tv = TextView(this).apply {
            textSize = 18f
            setTextColor(0xFFFFFF00.toInt()) // 亮黄色
            gravity = Gravity.CENTER
            // 背景完全透明
            setBackgroundColor(0x00000000)
            // 文字阴影增强可读性
            setShadowLayer(4f, 0f, 0f, 0xCC000000.toInt())
            // 初始隐藏
            visibility = View.GONE
        }
        subtitleTextView = tv

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            // 不获取焦点，不触摸穿透（但不可触摸）
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        // 顶部偏移，避开状态栏（约 60dp 转为像素）
        params.y = (60 * resources.displayMetrics.density).toInt()

        // 确保字幕在所有应用之上，但低于系统栏（保持可见）
        try {
            windowManager.addView(tv, params)
        } catch (e: Exception) {
            android.util.Log.e("FloatView", "添加字幕悬浮窗失败: ${e.message}")
        }
    }

    // ---------- 原有功能 ----------
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

        // 移除悬浮球
        try {
            windowManager.removeView(floatView)
        } catch (_: Exception) {
        }

        // 移除字幕
        subtitleTextView?.let {
            try {
                windowManager.removeView(it)
            } catch (_: Exception) {
            }
        }
        subtitleTextView = null
    }
}