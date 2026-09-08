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

        ensurePermissions()
        refreshModelState()
        refreshPermissionState()

        binding.btnDownload.setOnClickListener { startDownload() }

        binding.btnToggleService.setOnClickListener {
            if (VoiceAssistantService.isRunning) {
                VoiceAssistantService.stop(this)
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
                    handler.postDelayed({ finish() }, 500)
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

        // 唤醒灵敏度按钮
        binding.btnSensLow.setOnClickListener { setSensitivity(0) }
        binding.btnSensMedium.setOnClickListener { setSensitivity(1) }
        binding.btnSensHigh.setOnClickListener { setSensitivity(2) }
        binding.btnCalibration.setOnClickListener {
            startActivity(android.content.Intent(this, CalibrationActivity::class.java))
        }
        // 初始化灵敏度显示
        val savedSens = prefs.getInt(KEY_SENSITIVITY, 1)
        setSensitivity(savedSens, save = false)
    }

    // ===== 唤醒灵敏度设置 =====
    private fun setSensitivity(level: Int, save: Boolean = true) {
        WakeWordEngine.setSensitivity(level)
        if (save) {
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                .putInt(KEY_SENSITIVITY, level).apply()
        }
        // 更新按钮状态
        binding.btnSensLow.isEnabled = (level != 0)
        binding.btnSensMedium.isEnabled = (level != 1)
        binding.btnSensHigh.isEnabled = (level != 2)
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
        binding.tvVoiceState.text = when (VoiceAssistantService.currentState) {
            VoiceAssistantService.State.IDLE -> if (running) "待机：等待唤醒词…" else "—"
            VoiceAssistantService.State.LISTENING -> "聆听中…"
            VoiceAssistantService.State.PROCESSING -> "处理中…"
            VoiceAssistantService.State.SPEAKING -> "播报中…"
        }
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