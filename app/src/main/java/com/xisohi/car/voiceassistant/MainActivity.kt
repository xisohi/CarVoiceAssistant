package com.xisohi.car.voiceassistant

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.speech.tts.TextToSpeech
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
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
    // TTS 检测（只检测一次，避免每500ms创建销毁TTS实例的性能问题）
    private var ttsChecker: TextToSpeech? = null
    private var ttsChecked = false
    private var ttsAvailable = false
    private var ttsEngineName = "" 
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
                toast(getString(R.string.toast_no_microphone_permission))
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 返回后台运行按钮：只关闭页面，不停止服务
        binding.btnBackground.setOnClickListener {
            toast(getString(R.string.toast_running_in_background))
            finish()
        }

        // TTS 设置按钮：打开系统 TTS 设置页面
        binding.btnTtsSettings.setOnClickListener {
            openTtsSettings()
        }

        ensurePermissions()
        refreshModelState()
        refreshPermissionState()

        binding.btnDownload.setOnClickListener { startDownload() }

        binding.btnToggleService.setOnClickListener {
            if (VoiceAssistantService.isRunning) {
                VoiceAssistantService.stop(this)
                toast(getString(R.string.toast_service_stopped))
            } else {
                if (ModelManager.isModelReady(this)) {
                    if (!canDrawOverlays()) {
                        toast(getString(R.string.toast_need_overlay_permission))
                        openOverlaySettings()
                        return@setOnClickListener
                    }
                    // 无障碍服务不再强制要求（导航使用 URI scheme）
                    // if (!isAccessibilityEnabled()) {
                    //     toast("建议启用无障碍服务以获得完整的导航自动输入体验")
                    // }
                    VoiceAssistantService.start(this)
                    toast(getString(R.string.toast_service_started))
                } else {
                    toast(getString(R.string.toast_need_download_model))
                }
            }
        }

        // 开机自启开关
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        binding.switchAutoStart.isChecked = prefs.getBoolean(KEY_AUTO_START, true)
        binding.switchAutoStart.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean(KEY_AUTO_START, isChecked).apply()
            toast(if (isChecked) getString(R.string.toast_auto_start_enabled) else getString(R.string.toast_auto_start_disabled))
        }

        // 打开系统自启动设置按钮
        binding.btnOpenAutoStartSettings.setOnClickListener {
            openAutoStartSettings()
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

        // 检测 TTS 引擎可用性（只检测一次）
        checkTtsOnce()
        // 初始刷新一次服务状态
        refreshServiceState()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // 按返回键只关闭页面，不停止服务
        if (VoiceAssistantService.isRunning) {
            toast(getString(R.string.toast_running_in_background))
        }
        super.onBackPressed()
    }

    override fun onResume() {
        super.onResume()
        // 页面可见时启动状态定时刷新（每500ms）
        handler.post(stateRefresher)
        // 刷新权限状态
        refreshPermissionState()
    }

    override fun onPause() {
        super.onPause()
        // 页面不可见时停止状态刷新，节省资源
        handler.removeCallbacks(stateRefresher)
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(stateRefresher)
        // 释放 TTS 检测实例
        ttsChecker?.shutdown()
        ttsChecker = null
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

        // threshold 滑块：0.001 ~ 0.10，步长 0.001
        binding.seekThreshold.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                val threshold = (progress + 1) * 0.001f
                binding.tvThresholdValue.text = String.format("%.3f", threshold)
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
            val threshold = (binding.seekThreshold.progress + 1) * 0.001f
            val gain = 1.0f + binding.seekGain.progress * 0.1f
            WakeWordEngine.setGainAndThreshold(gain, threshold)
            // 保存到 SharedPreferences
            prefs.edit()
                .putFloat(KEY_MANUAL_THRESHOLD, threshold)
                .putFloat(KEY_MANUAL_GAIN, gain)
                .apply()
            // 更新灵敏度描述
            binding.tvSensitivityDesc.text = "当前：手动（增益${String.format("%.1f", gain)}x，阈值${String.format("%.3f", threshold)}）"
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
            toast(getString(R.string.toast_sensitivity_reset))
            log("已恢复默认灵敏度（中）")
        }
    }

    private fun updateManualUI(threshold: Float, gain: Float) {
        // threshold: 0.001 ~ 0.10 -> progress 0 ~ 99
        val thresholdProgress = ((threshold / 0.001f).toInt() - 1).coerceIn(0, 99)
        binding.seekThreshold.progress = thresholdProgress
        binding.tvThresholdValue.text = String.format("%.3f", threshold)
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
        binding.tvServiceState.text = if (running) getString(R.string.state_running) else getString(R.string.state_stopped)
        binding.btnToggleService.text = if (running) getString(R.string.btn_stop_service) else getString(R.string.btn_start_service)
        binding.btnBackground.isEnabled = running
        // 更新 TTS 状态
        updateTtsState()
        binding.tvVoiceState.text = when (VoiceAssistantService.currentState) {
            VoiceAssistantService.State.IDLE -> if (running) getString(R.string.voice_state_idle) else getString(R.string.voice_state_none)
            VoiceAssistantService.State.LISTENING -> getString(R.string.state_listening)
            VoiceAssistantService.State.PROCESSING -> getString(R.string.state_processing)
            VoiceAssistantService.State.SPEAKING -> getString(R.string.state_speaking)
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

    /**
     * 只检测一次 TTS 引擎可用性（在 onCreate 中调用）
     * 创建临时 TextToSpeech 实例，通过 onInit 回调判断是否可用
     */
    private fun checkTtsOnce() {
        if (ttsChecked) return
        try {
            ttsChecker = TextToSpeech(this) { status ->
                ttsChecked = true
                if (status == TextToSpeech.SUCCESS) {
                    ttsAvailable = true
                    try {
                        val engines = ttsChecker?.engines
                        val defaultEngine = ttsChecker?.defaultEngine
                        val engineInfo = engines?.find { it.name == defaultEngine }
                        ttsEngineName = engineInfo?.label ?: defaultEngine ?: getString(R.string.tts_default_engine)
                    } catch (_: Exception) {
                        ttsEngineName = getString(R.string.tts_default_engine)
                    }
                    android.util.Log.d("MainActivity", "TTS检测成功：$ttsEngineName")
                } else {
                    ttsAvailable = false
                    ttsEngineName = ""
                    android.util.Log.w("MainActivity", "TTS检测失败：status=$status")
                }
                // 检测完成后释放临时实例
                ttsChecker?.shutdown()
                ttsChecker = null
                // 刷新UI显示
                runOnUiThread { updateTtsState() }
            }
        } catch (e: Exception) {
            ttsChecked = true
            ttsAvailable = false
            android.util.Log.w("MainActivity", "TTS检测异常：${e.message}")
        }
    }

    /**
     * 更新 TTS 状态显示（根据 checkTtsOnce 的检测结果）
     * 有 TTS 引擎时用语音提示"在呢，您请说"，没有时自动降级为哔哔声
     */
    private fun updateTtsState() {
        if (!ttsChecked) {
            binding.tvTtsState.text = getString(R.string.tts_checking)
            binding.tvTtsState.setTextColor(getColor(android.R.color.darker_gray))
            binding.btnTtsSettings.text = getString(R.string.btn_tts_settings)
            return
        }
        if (ttsAvailable) {
            binding.tvTtsState.text = getString(R.string.tts_available, ttsEngineName)
            binding.tvTtsState.setTextColor(getColor(R.color.float_listening))
            binding.btnTtsSettings.text = getString(R.string.btn_tts_settings)
        } else {
            binding.tvTtsState.text = getString(R.string.tts_unavailable)
            binding.tvTtsState.setTextColor(getColor(R.color.float_processing))
            binding.btnTtsSettings.text = getString(R.string.btn_tts_install)
        }
    }

    /**
     * TTS 按钮点击事件：
     * - TTS 可用时：打开系统 TTS 设置页面
     * TTS 不可用时：跳转到应用商店搜索推荐的离线 TTS 引擎（讯飞语音+）
     */
    private fun openTtsSettings() {
        if (ttsAvailable) {
            // TTS 可用：打开系统 TTS 设置页面，用户可以切换引擎/调整语速
            try {
                val intent = Intent("com.android.settings.TTS_SETTINGS")
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
            } catch (e: Exception) {
                toast(getString(R.string.toast_tts_settings_failed))
            }
        } else {
            // TTS 不可用：跳转到应用商店搜索推荐的离线 TTS 引擎
            try {
                // 优先搜索讯飞语音+（国内最稳定的离线中文TTS）
                val intent = Intent(Intent.ACTION_VIEW,
                    android.net.Uri.parse("market://search?q=讯飞语音+ TTS 离线"))
                startActivity(intent)
                toast("请在应用商店搜索并安装「讯飞语音+」，安装后在系统设置中设为默认TTS引擎")
            } catch (e: Exception) {
                try {
                    // 备用：打开浏览器搜索讯飞语音+下载
                    val intent = Intent(Intent.ACTION_VIEW,
                        android.net.Uri.parse("https://lcjly.cn/car/讯飞语记v8.4.1459.apk"))
                    startActivity(intent)
                    toast("请下载并安装「讯飞语音+」，安装后在系统设置中设为默认TTS引擎")
                } catch (e2: Exception) {
                    toast("请手动在应用商店搜索「讯飞语音+」或「TTS」进行安装")
                }
            }
        }
    }

    private fun refreshModelState() {
        if (ModelManager.isModelReady(this)) {
            binding.tvModelState.text = getString(R.string.model_ready)
            binding.btnDownload.isEnabled = false
            binding.progressDownload.isIndeterminate = false
            binding.progressDownload.progress = 0
        } else {
            binding.tvModelState.text = getString(R.string.model_not_found)
            binding.btnDownload.isEnabled = true
        }
    }

    private fun refreshPermissionState() {
        if (canDrawOverlays()) {
            binding.tvOverlayState.text = getString(R.string.overlay_granted)
        } else {
            binding.tvOverlayState.text = getString(R.string.overlay_denied)
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

    /**
     * 打开系统自启动设置页面。
     * 尝试跳转到各个厂商的自启动管理页面，如果都失败则跳转到应用详情页。
     */
    private fun openAutoStartSettings() {
        // 各个厂商的自启动管理页面 Intent（按优先级排序）
        val autoStartIntents = listOf(
            // ===== 手机厂商 =====
            // 小米/红米
            Intent().apply {
                component = android.content.ComponentName(
                    "com.miui.securitycenter",
                    "com.miui.permcenter.autostart.AutoStartManagementActivity"
                )
            },
            // 华为/荣耀
            Intent().apply {
                component = android.content.ComponentName(
                    "com.huawei.systemmanager",
                    "com.huawei.systemmanager.optimize.process.ProtectActivity"
                )
            },
            // OPPO
            Intent().apply {
                component = android.content.ComponentName(
                    "com.coloros.safecenter",
                    "com.coloros.safecenter.permission.startup.StartupAppListActivity"
                )
            },
            // vivo
            Intent().apply {
                component = android.content.ComponentName(
                    "com.iqoo.secure",
                    "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"
                )
            },
            // 三星
            Intent().apply {
                component = android.content.ComponentName(
                    "com.samsung.android.sm",
                    "com.samsung.android.sm.ui.ram.AutoRunActivity"
                )
            },
            // ===== 通用 Android / 车机系统 =====
            // 通用：应用详情页（大多数系统都有）
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:$packageName")
            },
            // 通用：电池优化设置（设置电池为"不受限"可以防止系统杀后台）
            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
            // 通用：特殊应用访问
            Intent(Settings.ACTION_APPLICATION_SETTINGS),
            // 通用：所有应用列表
            Intent(Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS)
        )

        // 尝试跳转到各个自启动管理页面
        for (intent in autoStartIntents) {
            try {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
                android.util.Log.d("MainActivity", "成功跳转到设置: ${intent.action ?: intent.component?.packageName}")
                toast(getString(R.string.toast_settings_opened))
                return
            } catch (e: Exception) {
                android.util.Log.d("MainActivity", "跳转失败: ${intent.action ?: intent.component?.packageName} - ${e.message}")
            }
        }

        // 都失败了，显示详细的手动操作指引
        val message = getString(R.string.dialog_auto_start_message)
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.dialog_auto_start_title)
            .setMessage(message)
            .setPositiveButton(R.string.dialog_got_it, null)
            .show()
    }

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
                            getString(R.string.model_downloading, p.bytesDownloaded / 1024 / 1024, p.totalBytes / 1024 / 1024)
                    }
                }
                log("下载完成（${pack.length() / 1024 / 1024}MB），正在解压…")
                ModelInstaller.install(this@MainActivity, pack)
                log("模型安装完成")
                refreshModelState()
                toast(getString(R.string.toast_model_ready))
            } catch (e: Exception) {
                binding.btnDownload.isEnabled = true
                binding.progressDownload.isIndeterminate = false
                val msg = e.message ?: getString(R.string.toast_unknown_error)
                log("失败：$msg")
                toast(getString(R.string.toast_download_failed) + "：$msg")
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