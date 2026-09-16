package com.xisohi.car.voiceassistant

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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
import com.xisohi.car.voiceassistant.core.BaiduAsrManager
import com.xisohi.car.voiceassistant.core.LogUtils
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
        private const val KEY_MANUAL_ASR_GAIN = "asr_gain_override"
    }

    private lateinit var binding: ActivityMainBinding
    private lateinit var baiduAsrManager: BaiduAsrManager

    // 识别结果广播接收器：接收 VoiceAssistantService 发送的识别结果，显示到运行日志
    private val recognitionLogReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val message = intent?.getStringExtra(VoiceAssistantService.EXTRA_LOG_MESSAGE) ?: return
            log(message)
        }
    }

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

    // 文件选择器：用于从任意位置（U盘、车机内部存储等）选择百度配置文件
    private val filePickerLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                importBaiduConfigFromUri(uri)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 初始化文件日志系统
        LogUtils.init(this)

        // 注册识别结果广播接收器（实时显示识别结果到运行日志）
        val filter = IntentFilter(VoiceAssistantService.ACTION_RECOGNITION_LOG)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(recognitionLogReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(recognitionLogReceiver, filter)
        }

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

        // 设置 WorkManager 周期性自启动检查：即使开机广播收不到或服务被杀，
        // 每15分钟自动检查一次服务状态，未运行则自动启动（Android 12+ 后台启动被拒时发通知提醒）
        // 这是针对鼎微/全志车机系统的重要兜底机制
        BootReceiver.scheduleAutoStartCheck(this)

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

        // 地名管理按钮：打开地名管理页面
        binding.btnPlaceManager.setOnClickListener {
            startActivity(android.content.Intent(this, PlaceManagerActivity::class.java))
        }

        binding.btnLogViewer.setOnClickListener {
            startActivity(android.content.Intent(this, LogViewerActivity::class.java))
        }
        // 初始化灵敏度显示（如果有手动参数，显示手动；否则显示当前引擎参数）
        val savedThreshold = prefs.getFloat(KEY_MANUAL_THRESHOLD, -1f)
        val savedGain = prefs.getFloat(KEY_MANUAL_GAIN, -1f)
        if (savedThreshold > 0 && savedGain > 0) {
            binding.tvSensitivityDesc.text = "当前：（增益${String.format("%.1f", savedGain)}x，阈值${String.format("%.3f", savedThreshold)}）"
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
        val savedAsrGain = prefs.getFloat(KEY_MANUAL_ASR_GAIN, -1f)

        // 如果有保存的手动参数，应用它
        if (savedThreshold > 0 && savedGain > 0) {
            WakeWordEngine.setGainAndThreshold(savedGain, savedThreshold)
            updateManualUI(savedThreshold, savedGain)
            log("已加载手动参数：threshold=$savedThreshold, gain=${savedGain}x")
        } else {
            // 否则用当前引擎的值初始化 UI
            updateManualUI(WakeWordEngine.getDetectionThreshold(), WakeWordEngine.getAudioGain())
        }

        // 加载识别增益（asrGain）
        if (savedAsrGain >= 3.0f) {
            WakeWordEngine.setAsrGain(savedAsrGain)
            log("已加载识别增益：asrGain=${savedAsrGain}x")
        }
        updateAsrGainUI(WakeWordEngine.getAsrGain())

        // threshold 滑块：0.001 ~ 0.10，步长 0.001
        binding.seekThreshold.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                val threshold = (progress + 1) * 0.001f
                binding.tvThresholdValue.text = String.format("%.3f", threshold)
            }
            override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {}
        })

        // gain 滑块：1.0 ~ 5.5，步长 0.1
        binding.seekGain.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                val gain = 1.0f + progress * 0.1f
                binding.tvGainValue.text = String.format("%.1fx", gain)
            }
            override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {}
        })

        // asrGain 滑块：3.0 ~ 8.0，步长 0.1
        binding.seekAsrGain.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                val asrGain = 5.0f + progress * 0.1f
                binding.tvAsrGainValue.text = String.format("%.1fx", asrGain)
            }
            override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {}
        })

        // 应用手动参数
        binding.btnApplyManual.setOnClickListener {
            val threshold = (binding.seekThreshold.progress + 1) * 0.001f
            val gain = 1.0f + binding.seekGain.progress * 0.1f
            val asrGain = 5.0f + binding.seekAsrGain.progress * 0.1f
            WakeWordEngine.setGainAndThreshold(gain, threshold)
            WakeWordEngine.setAsrGain(asrGain)
            // 保存到 SharedPreferences
            prefs.edit()
                .putFloat(KEY_MANUAL_THRESHOLD, threshold)
                .putFloat(KEY_MANUAL_GAIN, gain)
                .putFloat(KEY_MANUAL_ASR_GAIN, asrGain)
                .apply()
            // 更新灵敏度描述
            binding.tvSensitivityDesc.text = "当前：手动（增益${String.format("%.1f", gain)}x，阈值${String.format("%.3f", threshold)}，识别增益${String.format("%.1f", asrGain)}x）"
            toast("已应用：threshold=$threshold, gain=${gain}x, asrGain=${asrGain}x")
            log("手动参数已应用：threshold=$threshold, gain=${gain}x, asrGain=${asrGain}x")
        }

        // 恢复默认（清除手动参数，用三档灵敏度）
        binding.btnResetManual.setOnClickListener {
            prefs.edit()
                .remove(KEY_MANUAL_THRESHOLD)
                .remove(KEY_MANUAL_GAIN)
                .remove(KEY_MANUAL_ASR_GAIN)
                .apply()
            // 恢复识别增益默认值
            WakeWordEngine.setAsrGain(8.0f)
            // 恢复到中档预设
            fillPresetToSliders(1)
            toast(getString(R.string.toast_sensitivity_reset))
            log("已恢复默认灵敏度（中）")
        }

        // 初始化百度语音识别配置
        baiduAsrManager = BaiduAsrManager.getInstance(this)
        binding.etBaiduAppId.setText(baiduAsrManager.getAppId())
        binding.etBaiduApiKey.setText(baiduAsrManager.getApiKey())
        binding.etBaiduSecretKey.setText(baiduAsrManager.getSecretKey())
        updateBaiduStatus()

        // 首次启动检测：如果未配置百度语音识别，弹出引导对话框
        if (!baiduAsrManager.isConfigured()) {
            showBaiduConfigGuide()
        }

        // 保存百度配置
        binding.btnSaveBaiduConfig.setOnClickListener {
            val appId = binding.etBaiduAppId.text.toString().trim()
            val apiKey = binding.etBaiduApiKey.text.toString().trim()
            val secretKey = binding.etBaiduSecretKey.text.toString().trim()
            if (appId.isEmpty() || apiKey.isEmpty() || secretKey.isEmpty()) {
                toast("App ID、API Key 和 Secret Key 都不能为空")
                return@setOnClickListener
            }
            baiduAsrManager.saveConfig(appId, apiKey, secretKey)
            toast("百度语音配置已保存")
            log("百度语音配置已保存")
            updateBaiduStatus()
        }

        // 测试百度配置（初始化 SDK 测试）
        binding.btnTestBaiduConfig.setOnClickListener {
            val appId = binding.etBaiduAppId.text.toString().trim()
            val apiKey = binding.etBaiduApiKey.text.toString().trim()
            val secretKey = binding.etBaiduSecretKey.text.toString().trim()
            if (appId.isEmpty() || apiKey.isEmpty() || secretKey.isEmpty()) {
                toast("请先填写 App ID、API Key 和 Secret Key")
                return@setOnClickListener
            }
            // 先保存配置
            baiduAsrManager.saveConfig(appId, apiKey, secretKey)
            binding.tvBaiduStatus.text = "正在初始化..."
            binding.tvBaiduStatus.setTextColor(0xFFFF9800.toInt())
            // 初始化 SDK 测试
            val success = baiduAsrManager.init()
            if (success) {
                binding.tvBaiduStatus.text = "初始化成功"
                binding.tvBaiduStatus.setTextColor(0xFF4CAF50.toInt())
                toast("百度语音 SDK 初始化成功")
                log("百度语音 SDK 初始化成功")
            } else {
                binding.tvBaiduStatus.text = "初始化失败，请检查配置"
                binding.tvBaiduStatus.setTextColor(0xFFF44336.toInt())
                toast("百度语音 SDK 初始化失败，请检查配置")
                log("百度语音 SDK 初始化失败")
            }
        }

        // 从文件导入百度配置（支持U盘、车机内部存储等任意位置）
        binding.btnImportBaiduConfig.setOnClickListener {
            try {
                // 先尝试系统文件选择器
                filePickerLauncher.launch(arrayOf("application/json", "*/*"))
                log("打开文件选择器，选择百度配置文件")
            } catch (e: Exception) {
                log("系统文件选择器不可用：${e.message}，尝试扫描U盘")
                // 系统没有文件选择器（如精简版车机系统），自动扫描U盘
                scanUsbAndImport()
            }
        }
    }

    /**
     * 扫描U盘并导入配置文件（系统没有文件选择器时的 fallback）
     * 自动扫描常见U盘路径下的 .json 文件
     */
    private fun scanUsbAndImport() {
        // 常见的U盘挂载路径
        val usbPaths = listOf(
            "/storage/usb1",
            "/storage/usb0",
            "/mnt/usb",
            "/mnt/usb_storage",
            "/storage/udisk",
            "/mnt/udisk"
        )

        val jsonFiles = mutableListOf<java.io.File>()

        // 扫描每个路径下的 .json 文件
        for (path in usbPaths) {
            val dir = java.io.File(path)
            if (dir.exists() && dir.isDirectory) {
                log("扫描U盘路径: $path")
                try {
                    dir.listFiles { file ->
                        file.isFile && file.name.lowercase().endsWith(".json")
                    }?.let { files ->
                        jsonFiles.addAll(files)
                        log("  找到 ${files.size} 个 .json 文件")
                    }
                } catch (e: Exception) {
                    log("  扫描失败: ${e.message}")
                }
            }
        }

        when {
            jsonFiles.isEmpty() -> {
                toast("未在U盘找到 .json 配置文件请将配置文件放到U盘根目录")
                log("U盘扫描完成：未找到 .json 文件")
            }
            jsonFiles.size == 1 -> {
                // 只有一个文件，直接导入
                log("U盘只找到一个配置文件，直接导入: ${jsonFiles[0].name}")
                importBaiduConfigFromFile(jsonFiles[0])
            }
            else -> {
                // 多个文件，弹出选择对话框
                log("U盘找到 ${jsonFiles.size} 个配置文件，弹出选择对话框")
                showFileSelectDialog(jsonFiles)
            }
        }
    }

    /**
     * 弹出文件选择对话框（多个配置文件时让用户选择）
     */
    private fun showFileSelectDialog(files: List<java.io.File>) {
        val fileNames = files.map { it.name }.toTypedArray()
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("选择配置文件")
            .setItems(fileNames) { _, which ->
                importBaiduConfigFromFile(files[which])
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /**
     * 从本地文件导入百度语音配置
     */
    private fun importBaiduConfigFromFile(file: java.io.File) {
        try {
            val content = file.readText(Charsets.UTF_8)
            log("导入配置：读取文件成功，内容长度=${content.length}")

            // 解析 JSON
            val json = org.json.JSONObject(content)
            val appId = json.optString("app_id", "").trim()
            val apiKey = json.optString("api_key", "").trim()
            val secretKey = json.optString("secret_key", "").trim()

            if (appId.isEmpty() || apiKey.isEmpty() || secretKey.isEmpty()) {
                toast("配置文件格式错误，请检查 app_id/api_key/secret_key 是否完整")
                log("导入配置失败：配置不完整")
                return
            }

            // 填充到输入框
            binding.etBaiduAppId.setText(appId)
            binding.etBaiduApiKey.setText(apiKey)
            binding.etBaiduSecretKey.setText(secretKey)

            // 自动保存配置
            baiduAsrManager.saveConfig(appId, apiKey, secretKey)
            updateBaiduStatus()

            toast("配置导入成功！已自动保存")
            log("导入配置成功：appId=$appId, 来源=${file.name}")

        } catch (e: Exception) {
            toast("导入失败：${e.message}")
            log("导入配置异常：${e.message}")
        }
    }

    /**
     * 从用户选择的文件导入百度语音配置
     * 支持从任意位置（U盘、车机内部存储等）选择文件
     * 配置文件格式：{"app_id":"xxx","api_key":"xxx","secret_key":"xxx"}
     */
    private fun importBaiduConfigFromUri(uri: android.net.Uri) {
        try {
            // 通过 ContentResolver 读取文件内容
            val inputStream = contentResolver.openInputStream(uri)
            if (inputStream == null) {
                toast("无法读取文件")
                log("导入配置失败：无法打开文件输入流")
                return
            }

            val content = inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            inputStream.close()
            log("导入配置：读取文件成功，内容长度=${content.length}")

            // 解析 JSON
            val json = org.json.JSONObject(content)
            val appId = json.optString("app_id", "").trim()
            val apiKey = json.optString("api_key", "").trim()
            val secretKey = json.optString("secret_key", "").trim()

            if (appId.isEmpty() || apiKey.isEmpty() || secretKey.isEmpty()) {
                toast("配置文件格式错误，请检查 app_id/api_key/secret_key 是否完整")
                log("导入配置失败：配置不完整")
                return
            }

            // 填充到输入框
            binding.etBaiduAppId.setText(appId)
            binding.etBaiduApiKey.setText(apiKey)
            binding.etBaiduSecretKey.setText(secretKey)

            // 自动保存配置
            baiduAsrManager.saveConfig(appId, apiKey, secretKey)
            updateBaiduStatus()

            toast("配置导入成功！已自动保存")
            log("导入配置成功：appId=$appId")

        } catch (e: Exception) {
            toast("导入失败：${e.message}")
            log("导入配置异常：${e.message}")
        }
    }

    /**
     * 更新百度语音配置状态显示
     */
    private fun updateBaiduStatus() {
        if (baiduAsrManager.isConfigured()) {
            binding.tvBaiduStatus.text = "已配置"
            binding.tvBaiduStatus.setTextColor(0xFF4CAF50.toInt())
        } else {
            binding.tvBaiduStatus.text = "未配置"
            binding.tvBaiduStatus.setTextColor(0xFF9E9E9E.toInt())
        }
    }

    /**
     * 显示百度语音识别配置引导对话框
     * 首次启动且未配置时弹出，引导用户去百度智能云创建应用并配置参数
     */
    private fun showBaiduConfigGuide() {
        val dialog = android.app.AlertDialog.Builder(this)
            .setTitle("配置百度语音识别")
            .setMessage(
                "百度语音识别（在线）识别率更高，需要您使用自己的百度账号进行配置：\n\n" +
                "1. 打开百度智能云官网（console.bce.baidu.com）\n" +
                "2. 进入「语音技术」→「应用管理」→「创建应用」\n" +
                "3. 开通「短语音识别」服务（个人认证免费15万次）\n" +
                "4. 复制 App ID、API Key、Secret Key 填入下方设置页\n\n" +
                "未配置时将使用离线 Vosk 识别（准确率较低）。\n\n" +
                "是否现在配置？"
            )
            .setPositiveButton("立即配置") { _, _ ->
                // 滚动到百度配置区域
                binding.scrollView.post {
                    binding.scrollView.smoothScrollTo(0, binding.cardBaiduConfig.top)
                }
                // 聚焦到 App ID 输入框
                binding.etBaiduAppId.requestFocus()
                toast("请在下方填写您的百度语音识别配置")
            }
            .setNegativeButton("稍后再说") { dialog, _ ->
                dialog.dismiss()
            }
            .setCancelable(false)
            .create()
        dialog.show()
    }

    private fun updateManualUI(threshold: Float, gain: Float) {
        // threshold: 0.001 ~ 0.10 -> progress 0 ~ 99
        val thresholdProgress = ((threshold / 0.001f).toInt() - 1).coerceIn(0, 99)
        binding.seekThreshold.progress = thresholdProgress
        binding.tvThresholdValue.text = String.format("%.3f", threshold)
        // gain: 1.0 ~ 5.5 -> progress 0 ~ 45
        val gainProgress = ((gain - 1.0f) / 0.1f).toInt().coerceIn(0, 45)
        binding.seekGain.progress = gainProgress
        binding.tvGainValue.text = String.format("%.1fx", gain)
    }

    /**
     * 更新识别增益（asrGain）滑块 UI
     * asrGain: 3.0 ~ 8.0 -> progress 0 ~ 50
     */
    private fun updateAsrGainUI(asrGain: Float) {
        val asrGainProgress = ((asrGain - 5.0f) / 0.1f).toInt().coerceIn(0, 50)
        binding.seekAsrGain.progress = asrGainProgress
        binding.tvAsrGainValue.text = String.format("%.1fx", asrGain)
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
     * 未选中的按钮：透明背景 + 灰色文字（无描边，不高亮）
     */
    private fun updatePresetButtonState(selectedLevel: Int) {
        val buttons = listOf(
            binding.btnSensLow to 0,
            binding.btnSensMedium to 1,
            binding.btnSensHigh to 2
        )
        val accentColor = getColor(R.color.brand_blue)
        val whiteColor = getColor(android.R.color.white)
        val grayColor = getColor(android.R.color.darker_gray)
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
                // 未选中：透明背景 + 灰色文字（无描边，不高亮）
                btn.setBackgroundColor(android.graphics.Color.TRANSPARENT)
                btn.setTextColor(grayColor)
                btn.strokeWidth = 0
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
        // 刷新最近意图结果
        val lastIntent = VoiceAssistantService.lastIntentResult
        binding.tvLastIntent.text = lastIntent

        // 刷新录音 RMS 峰值（车机上看不到日志，峰值更有意义）
        // 显示本次录音的峰值，下次录音开始时重置
        // 注意：这里的 RMS 是增益后的值，不是原始音频的 RMS
        // 合理范围：5000~10000 最佳，10000~15000 可接受但偏高，>15000 削顶风险
        val rms = VoiceAssistantService.peakRms
        val rmsStatus = when {
            rms == 0 -> "待机"
            rms < 5000 -> "偏低"
            rms < 10000 -> "✅最佳"
            rms < 15000 -> "偏高"
            rms < 20000 -> "⚠️削顶风险"
            else -> "❌严重削顶"
        }
        binding.tvRms.text = "录音峰值: $rms ($rmsStatus)"
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
        // 注意：不再申请 WRITE_EXTERNAL_STORAGE / READ_EXTERNAL_STORAGE！
        // 原因：
        // 1. 日志、录音、配置都保存在 getExternalFilesDir() 应用私有目录，不需要存储权限
        // 2. U 盘读写通过 StorageManager 获取真实路径（/storage/usb1 等），直接用 File API 访问，不需要存储权限
        // 3. Android 10+ requestLegacyExternalStorage 已被 Google Play 标记为废弃，上架时会被审核卡住
        // 4. Android 11+ 分区存储（Scoped Storage）是标准做法，应用私有目录访问不需要任何权限
        val missing = needed.filter { !hasPermission(it) }
        if (missing.isNotEmpty()) {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    private fun hasPermission(p: String): Boolean =
        ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}