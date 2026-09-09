package com.xisohi.car.voiceassistant

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.xisohi.car.voiceassistant.core.VoiceAssistantService
import com.xisohi.car.voiceassistant.core.wakeword.WakeWordEngine
import com.xisohi.car.voiceassistant.databinding.ActivityMainBinding
import com.xisohi.car.voiceassistant.download.ModelDownloader
import com.xisohi.car.voiceassistant.download.ModelInstaller
import com.xisohi.car.voiceassistant.download.ModelManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    companion object {
        private const val PREFS_NAME = "voice_assistant_prefs"
        private const val KEY_AUTO_START = "auto_start_on_boot"
        private const val KEY_SENSITIVITY = "wake_sensitivity"
        private const val KEY_MANUAL_THRESHOLD = "wake_threshold_override"
        private const val KEY_MANUAL_GAIN = "wake_gain_override"
    }

    private lateinit var binding: ActivityMainBinding
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val handler = Handler(Looper.getMainLooper())
    private val stateRefresher = object : Runnable {
        override fun run() {
            refreshServiceState()
            handler.postDelayed(this, 500)
        }
    }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            if (result[Manifest.permission.RECORD_AUDIO] != true) {
                toast("未授予录音权限，语音助手无法工作")
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 返回后台运行按钮：只关闭页面，不停止服务
        binding.btnBackground.setOnClickListener {
            toast("语音助手在后台继续运行")
            finish()
        }

        ensurePermissions()
        refreshModelState()
        refreshPermissionState()

        binding.btnDownload.setOnClickListener { startDownload() }

        binding.btnToggleService.setOnClickListener {
            if (VoiceAssistantService.isRunning) {
                VoiceAssistantService.stop(this)
                toast("语音助手已停止")
            } else {
                if (ModelManager.isModelReady(this)) {
                    if (!canDrawOverlays()) {
                        toast("请先授予悬浮窗权限")
                        openOverlaySettings()
                        return@setOnClickListener
                    }
                    if (!isAccessibilityEnabled()) {
                        toast("建议启用无障碍服务以获得完整的导航自动输入体验")
                    }
                    VoiceAssistantService.start(this)
                    toast("语音助手已启动，点击「返回后台运行」关闭本页面")
                } else {
                    toast("请先下载离线语音包")
                }
            }
        }

        binding.btnAccessibility.setOnClickListener {
            openAccessibilitySettings()
        }

        // 开机自启开关
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        binding.switchAutoStart.isChecked = prefs.getBoolean(KEY_AUTO_START, true)
        binding.switchAutoStart.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean(KEY_AUTO_START, isChecked).apply()
            toast(if (isChecked) "已开启开机自启动" else "已关闭开机自启动")
        }

        // 唤醒灵敏度预设按钮（点击后填充到手动调节滑块，可微调后再应用）
        binding.btnSensLow.setOnClickListener { fillPresetToSliders(0) }
        binding.btnSensMedium.setOnClickListener { fillPresetToSliders(1) }
        binding.btnSensHigh.setOnClickListener { fillPresetToSliders(2) }
        binding.btnCalibration.setOnClickListener {
            startActivity(android.content.Intent(this, CalibrationActivity::class.java))
        }
        // 初始化灵敏度显示（如果有手动参数，显示手动；否则显示当前引擎参数）
        val savedThreshold = prefs.getFloat(KEY_MANUAL_THRESHOLD, -1f)
        val savedGain = prefs.getFloat(KEY_MANUAL_GAIN, -1f)
        if (savedThreshold > 0 && savedGain > 0) {
            binding.tvSensitivityDesc.text = "当前：手动（增益${String.format("%.1f", savedGain)}x，阈值${String.format("%.2f", savedThreshold)}）"
            // 有手动参数时，不高亮任何预设按钮
            binding.btnSensLow.isEnabled = true
            binding.btnSensMedium.isEnabled = true
            binding.btnSensHigh.isEnabled = true
        } else {
            val gain = WakeWordEngine.getAudioGain()
            val threshold = WakeWordEngine.getDetectionThreshold()
            binding.tvSensitivityDesc.text = "当前：${WakeWordEngine.getSensitivityName()}（增益${gain}x，阈值$threshold）"
            // 无手动参数时，高亮当前引擎对应的预设
            val currentLevel = WakeWordEngine.getSensitivity()
            updatePresetButtonState(currentLevel)
        }

        // 初始化手动调节
        initManualControls()

        // 初始刷新一次服务状态
        refreshServiceState()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // 按返回键只关闭页面，不停止服务
        if (VoiceAssistantService.isRunning) {
            toast("语音助手在后台继续运行")
        }
        super.onBackPressed()
    }

    override fun onResume() {
        super.onResume()
        // 页面可见时启动状态定时刷新（每500ms）
        handler.post(stateRefresher)
    }

    override fun onPause() {
        super.onPause()
        // 页面不可见时停止状态刷新，节省资源
        handler.removeCallbacks(stateRefresher)
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(stateRefresher)
    }

    // ===== 手动调节 threshold/gain =====
    private fun initManualControls() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)

        // 读取已保存的手动参数（如果有）
        val savedThreshold = prefs.getFloat(KEY_MANUAL_THRESHOLD, -1f)
        val savedGain = prefs.getFloat(KEY_MANUAL_GAIN, -1f)

        // 如果有保存的手动参数，应用它
        if (savedThreshold > 0 && savedGain > 0) {
            WakeWordEngine.setGainAndThreshold(savedGain, savedThreshold)
            updateManualUI(savedThreshold, savedGain)
            log("已加载手动参数：threshold=$savedThreshold, gain=${savedGain}x")
        } else {
            // 否则用当前引擎的值初始化 UI
            updateManualUI(WakeWordEngine.getDetectionThreshold(), WakeWordEngine.getAudioGain())
        }

        // threshold 滑块：0.01 ~ 0.50，步长 0.01
        binding.seekThreshold.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                val threshold = (progress + 1) / 100f
                binding.tvThresholdValue.text = String.format("%.2f", threshold)
            }
            override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {}
        })

        // gain 滑块：1.0 ~ 5.0，步长 0.1
        binding.seekGain.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                val gain = 1.0f + progress * 0.1f
                binding.tvGainValue.text = String.format("%.1fx", gain)
            }
            override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {}
        })

        // 应用手动参数
        binding.btnApplyManual.setOnClickListener {
            val threshold = (binding.seekThreshold.progress + 1) / 100f
            val gain = 1.0f + binding.seekGain.progress * 0.1f
            WakeWordEngine.setGainAndThreshold(gain, threshold)
            // 保存到 SharedPreferences
            prefs.edit()
                .putFloat(KEY_MANUAL_THRESHOLD, threshold)
                .putFloat(KEY_MANUAL_GAIN, gain)
                .apply()
            // 更新灵敏度描述
            binding.tvSensitivityDesc.text = "当前：手动（增益${String.format("%.1f", gain)}x，阈值${String.format("%.2f", threshold)}）"
            toast("已应用手动参数：threshold=$threshold, gain=${gain}x")
            log("手动参数已应用：threshold=$threshold, gain=${gain}x")
        }

        // 恢复默认（清除手动参数，用三档灵敏度）
        binding.btnResetManual.setOnClickListener {
            prefs.edit()
                .remove(KEY_MANUAL_THRESHOLD)
                .remove(KEY_MANUAL_GAIN)
                .apply()
            // 恢复到中档预设
            fillPresetToSliders(1)
            toast("已恢复默认灵敏度（中）")
            log("已恢复默认灵敏度（中）")
        }
    }

    private fun updateManualUI(threshold: Float, gain: Float) {
        // threshold: 0.01 ~ 0.50 -> progress 0 ~ 49
        val thresholdProgress = ((threshold * 100).toInt() - 1).coerceIn(0, 49)
        binding.seekThreshold.progress = thresholdProgress
        binding.tvThresholdValue.text = String.format("%.2f", threshold)
        // gain: 1.0 ~ 5.0 -> progress 0 ~ 40
        val gainProgress = ((gain - 1.0f) / 0.1f).toInt().coerceIn(0, 40)
        binding.seekGain.progress = gainProgress
        binding.tvGainValue.text = String.format("%.1fx", gain)
    }

    /**
     * 将预设灵敏度（低/中/高）的参数填充到手动调节滑块
     * 用户可以在此基础上微调，然后点击"应用手动参数"生效
     */
    private fun fillPresetToSliders(level: Int) {
        // 临时设置到引擎以获取对应的参数值
        WakeWordEngine.setSensitivity(level)
        val threshold = WakeWordEngine.getDetectionThreshold()
        val gain = WakeWordEngine.getAudioGain()
        val name = WakeWordEngine.getSensitivityName()
        // 填充到滑块
        updateManualUI(threshold, gain)
        // 更新按钮选中状态（禁用当前选中的按钮，启用其他按钮）
        updatePresetButtonState(level)
        // 提示用户
        toast("已填充「$name」预设（增益${gain}x，阈值$threshold），可微调后点击应用")
        log("预设「$name」已填充到滑块：threshold=$threshold, gain=${gain}x")
    }

    // ===== 唤醒灵敏度设置 =====
    /**
     * 更新三档预设按钮的选中高亮状态
     * 选中的按钮：填充背景（主题色）+ 白色文字
     * 未选中的按钮：透明背景 + 主题色文字（描边效果）
     */
    private fun updatePresetButtonState(selectedLevel: Int) {
        val buttons = listOf(
            binding.btnSensLow to 0,
            binding.btnSensMedium to 1,
            binding.btnSensHigh to 2
        )
        val accentColor = getColor(R.color.brand_blue)
        val whiteColor = getColor(android.R.color.white)
        for ((btn, level) in buttons) {
            val selected = (level == selectedLevel)
            btn.isSelected = selected
            btn.isEnabled = true  // 所有按钮都保持可点击
            if (selected) {
                // 选中：填充背景
                btn.setBackgroundColor(accentColor)
                btn.setTextColor(whiteColor)
                btn.strokeWidth = 0
            } else {
                // 未选中：透明背景 + 描边
                btn.setBackgroundColor(android.graphics.Color.TRANSPARENT)
                btn.setTextColor(accentColor)
                btn.strokeWidth = (2 * resources.displayMetrics.density).toInt()
            }
        }
    }

    private fun setSensitivity(level: Int, save: Boolean = true) {
        WakeWordEngine.setSensitivity(level)
        if (save) {
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                .putInt(KEY_SENSITIVITY, level).apply()
        }
        // 更新按钮状态
        updatePresetButtonState(level)
        // 更新描述
        val gain = WakeWordEngine.getAudioGain()
        val threshold = WakeWordEngine.getDetectionThreshold()
        binding.tvSensitivityDesc.text = "当前：${WakeWordEngine.getSensitivityName()}（增益${gain}x，阈值$threshold）"
        if (save) {
            toast("灵敏度已设为：${WakeWordEngine.getSensitivityName()}")
            log("唤醒灵敏度设置为：${WakeWordEngine.getSensitivityName()}（增益${gain}x，阈值$threshold）")
        }
    }

    private fun refreshServiceState() {
        val running = VoiceAssistantService.isRunning
        binding.tvServiceState.text = if (running) "● 运行中" else "○ 已停止"
        binding.btnToggleService.text = if (running) "停止服务" else "启动服务"
        binding.btnBackground.isEnabled = running
        binding.tvVoiceState.text = when (VoiceAssistantService.currentState) {
            VoiceAssistantService.State.IDLE -> if (running) "待机：等待唤醒词…" else "—"
            VoiceAssistantService.State.LISTENING -> "聆听中…"
            VoiceAssistantService.State.PROCESSING -> "处理中…"
            VoiceAssistantService.State.SPEAKING -> "播报中…"
        }
        // 刷新识别文本：聆听中显示实时识别结果，否则显示最终识别结果
        val isListening = (VoiceAssistantService.currentState == VoiceAssistantService.State.LISTENING)
        val partialText = VoiceAssistantService.lastPartialText
        val finalText = VoiceAssistantService.lastRecognizedText
        when {
            isListening && partialText.isNotBlank() -> {
                binding.tvLastRecognized.text = "💬 $partialText"
                binding.tvLastRecognized.setTextColor(android.graphics.Color.parseColor("#1565C0"))
            }
            finalText.isNotBlank() -> {
                binding.tvLastRecognized.text = finalText
                binding.tvLastRecognized.setTextColor(android.graphics.Color.parseColor("#1A1B1C"))
            }
            else -> {
                binding.tvLastRecognized.text = "—"
                binding.tvLastRecognized.setTextColor(android.graphics.Color.parseColor("#1A1B1C"))
            }
        }
        val lastIntent = VoiceAssistantService.lastIntentResult
        binding.tvLastIntent.text = lastIntent
    }

    private fun refreshModelState() {
        if (ModelManager.isModelReady(this)) {
            binding.tvModelState.text = "模型已就绪（完全离线可用）"
            binding.btnDownload.isEnabled = false
            binding.progressDownload.isIndeterminate = false
            binding.progressDownload.progress = 0
        } else {
            binding.tvModelState.text = "未检测到离线语音包"
            binding.btnDownload.isEnabled = true
        }
    }

    private fun refreshPermissionState() {
        if (canDrawOverlays()) {
            binding.tvOverlayState.text = "● 已授权"
        } else {
            binding.tvOverlayState.text = "○ 未授权（点击授权）"
        }
        if (isAccessibilityEnabled()) {
            binding.tvAccessibilityState.text = "● 已启用（导航自动填入）"
            binding.btnAccessibility.text = "无障碍服务已启用"
        } else {
            binding.tvAccessibilityState.text = "○ 未启用（导航需手动输入）"
            binding.btnAccessibility.text = "去启用无障碍服务"
        }
    }

    // ---------- 权限及设置跳转 ----------
    private fun canDrawOverlays(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(this)
        } else {
            true
        }
    }

    private fun openOverlaySettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
        }
    }

    private fun isAccessibilityEnabled(): Boolean {
        val am = getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        val enabledServices = am.getEnabledAccessibilityServiceList(
            AccessibilityServiceInfo.FEEDBACK_ALL_MASK
        )
        return enabledServices.any {
            it.resolveInfo?.serviceInfo?.packageName == packageName
        }
    }

    private fun openAccessibilitySettings() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                val extraKey = "android.provider.extra.ACCESSIBILITY_SERVICE_COMPONENT_NAME"
                intent.putExtra(
                    extraKey,
                    ComponentName(packageName, "com.xisohi.car.voiceassistant.core.AutoInputService").flattenToString()
                )
                startActivity(intent)
                toast("请找到「车载语音助手」并开启无障碍服务")
            } else {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        } catch (e: Exception) {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            toast("请在辅助功能列表中找到并启用本应用")
        }
    }

    // ---------- 首次下载 ----------
    private fun startDownload() {
        binding.btnDownload.isEnabled = false
        binding.progressDownload.isIndeterminate = true
        log("开始下载离线语音包…")
        scope.launch {
            try {
                val pack = ModelDownloader(this@MainActivity).download { p ->
                    runOnUiThread {
                        binding.progressDownload.isIndeterminate = false
                        binding.progressDownload.max = 100
                        binding.progressDownload.progress = (p.fraction * 100).toInt()
                        binding.tvModelState.text =
                            "下载中 ${p.bytesDownloaded / 1024 / 1024}MB / ${p.totalBytes / 1024 / 1024}MB"
                    }
                }
                log("下载完成（${pack.length() / 1024 / 1024}MB），正在解压…")
                ModelInstaller.install(this@MainActivity, pack)
                log("模型安装完成")
                refreshModelState()
                toast("模型就绪，可启动语音助手")
            } catch (e: Exception) {
                binding.btnDownload.isEnabled = true
                binding.progressDownload.isIndeterminate = false
                val msg = e.message ?: "未知错误"
                log("失败：$msg")
                toast("下载失败：$msg")
            }
        }
    }

    // ---------- 日志与权限 ----------
    private fun log(msg: String) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.CHINA).format(Date())
        binding.tvLog.append("[$time] $msg\n")
        binding.scrollLog.post { binding.scrollLog.fullScroll(android.view.View.FOCUS_DOWN) }
    }

    private fun ensurePermissions() {
        val needed = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) needed.add(Manifest.permission.POST_NOTIFICATIONS)
        if (Build.VERSION.SDK_INT >= 31) needed.add(Manifest.permission.BLUETOOTH_CONNECT)
        // 存储权限（保存测试日志）
        if (Build.VERSION.SDK_INT <= 28) needed.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        if (Build.VERSION.SDK_INT <= 32) needed.add(Manifest.permission.READ_EXTERNAL_STORAGE)
        val missing = needed.filter { !hasPermission(it) }
        if (missing.isNotEmpty()) {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    private fun hasPermission(p: String): Boolean =
        ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}