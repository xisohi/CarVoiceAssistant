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
import com.xisohi.car.voiceassistant.core.VerifyResult
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
            if (result[Manifest.permission.CALL_PHONE] != true) {
                toast("未授予拨打电话权限，打电话功能将只能打开拨号界面")
            }
            if (result[Manifest.permission.READ_CONTACTS] != true) {
                toast("未授予联系人权限，将无法通过姓名拨打电话")
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

        // ★ 先初始化日志，保证所有日志都能写文件
        LogUtils.init(this)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // SAF 可用性检测（只查不弹）
        try {
            val safIntent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
            val list = packageManager.queryIntentActivities(safIntent, 0)
            log("SAF 可用性: 找到 ${list.size} 个 Activity 处理 ACTION_OPEN_DOCUMENT_TREE")
            list.forEach {
                log("  - ${it.activityInfo.packageName}/${it.activityInfo.name}")
            }
            if (list.isEmpty()) {
                log("⚠️ 车机没有 DocumentsUI，SAF 方案不可行")
            } else {
                log("✅ 车机支持 SAF")
            }
        } catch (e: Exception) {
            log("查询 SAF 可用性失败: ${e.message}")
        }

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
            binding.btnSensLow.isEnabled = true
            binding.btnSensMedium.isEnabled = true
            binding.btnSensHigh.isEnabled = true
        } else {
            val gain = WakeWordEngine.getAudioGain()
            val threshold = WakeWordEngine.getDetectionThreshold()
            binding.tvSensitivityDesc.text = "当前：${WakeWordEngine.getSensitivityName()}（增益${gain}x，阈值$threshold）"
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
        handler.post(stateRefresher)
        refreshPermissionState()
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(stateRefresher)
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(stateRefresher)
        ttsChecker?.shutdown()
        ttsChecker = null
    }

    // ===== 手动调节 threshold/gain =====
    private fun initManualControls() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)

        val savedThreshold = prefs.getFloat(KEY_MANUAL_THRESHOLD, -1f)
        val savedGain = prefs.getFloat(KEY_MANUAL_GAIN, -1f)
        val savedAsrGain = prefs.getFloat(KEY_MANUAL_ASR_GAIN, -1f)

        if (savedThreshold > 0 && savedGain > 0) {
            WakeWordEngine.setGainAndThreshold(savedGain, savedThreshold)
            updateManualUI(savedThreshold, savedGain)
            log("已加载手动参数：threshold=$savedThreshold, gain=${savedGain}x")
        } else {
            updateManualUI(WakeWordEngine.getDetectionThreshold(), WakeWordEngine.getAudioGain())
        }

        if (savedAsrGain >= 3.0f) {
            WakeWordEngine.setAsrGain(savedAsrGain)
            log("已加载识别增益：asrGain=${savedAsrGain}x")
        }
        updateAsrGainUI(WakeWordEngine.getAsrGain())

        binding.seekThreshold.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                val threshold = (progress + 1) * 0.001f
                binding.tvThresholdValue.text = String.format("%.3f", threshold)
            }
            override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {}
        })

        binding.seekGain.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                val gain = 1.0f + progress * 0.1f
                binding.tvGainValue.text = String.format("%.1fx", gain)
            }
            override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {}
        })

        binding.seekAsrGain.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                val asrGain = 5.0f + progress * 0.1f
                binding.tvAsrGainValue.text = String.format("%.1fx", asrGain)
            }
            override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {}
        })

        binding.btnApplyManual.setOnClickListener {
            val threshold = (binding.seekThreshold.progress + 1) * 0.001f
            val gain = 1.0f + binding.seekGain.progress * 0.1f
            val asrGain = 5.0f + binding.seekAsrGain.progress * 0.1f
            WakeWordEngine.setGainAndThreshold(gain, threshold)
            WakeWordEngine.setAsrGain(asrGain)
            prefs.edit()
                .putFloat(KEY_MANUAL_THRESHOLD, threshold)
                .putFloat(KEY_MANUAL_GAIN, gain)
                .putFloat(KEY_MANUAL_ASR_GAIN, asrGain)
                .apply()
            binding.tvSensitivityDesc.text = "当前：手动（增益${String.format("%.1f", gain)}x，阈值${String.format("%.3f", threshold)}，识别增益${String.format("%.1f", asrGain)}x）"
            toast("已应用：threshold=$threshold, gain=${gain}x, asrGain=${asrGain}x")
            log("手动参数已应用：threshold=$threshold, gain=${gain}x, asrGain=${asrGain}x")
        }

        binding.btnResetManual.setOnClickListener {
            prefs.edit()
                .remove(KEY_MANUAL_THRESHOLD)
                .remove(KEY_MANUAL_GAIN)
                .remove(KEY_MANUAL_ASR_GAIN)
                .apply()
            WakeWordEngine.setAsrGain(8.0f)
            fillPresetToSliders(1)
            toast(getString(R.string.toast_sensitivity_reset))
            log("已恢复默认灵敏度（中）")
        }

        baiduAsrManager = BaiduAsrManager.getInstance(this)
        binding.etBaiduAppId.setText(baiduAsrManager.getAppId())
        binding.etBaiduApiKey.setText(baiduAsrManager.getApiKey())
        binding.etBaiduSecretKey.setText(baiduAsrManager.getSecretKey())
        updateBaiduStatus()

        if (!baiduAsrManager.isConfigured()) {
            showBaiduConfigGuide()
        }

        binding.btnSaveBaiduConfig.setOnClickListener {
            val appId = binding.etBaiduAppId.text.toString().trim()
            val apiKey = binding.etBaiduApiKey.text.toString().trim()
            val secretKey = binding.etBaiduSecretKey.text.toString().trim()
            if (appId.isEmpty() || apiKey.isEmpty() || secretKey.isEmpty()) {
                toast("App ID、API Key 和 Secret Key 都不能为空")
                return@setOnClickListener
            }
            log("保存百度配置：appId=$appId, apiKey=${apiKey.take(4)}...${apiKey.takeLast(4)}, secretKey=${secretKey.take(4)}...${secretKey.takeLast(4)}")
            baiduAsrManager.saveConfig(appId, apiKey, secretKey)
            val success = baiduAsrManager.init()
            if (success) {
                toast("百度语音配置已保存，请点测试连接验证")
                log("✅ 百度语音配置已保存并初始化成功（未验证，请点测试连接）")
                binding.tvBaiduStatus.text = "已配置（未验证，请点测试连接）"
                binding.tvBaiduStatus.setTextColor(0xFFFF9800.toInt())
            } else {
                toast("百度语音配置已保存，但初始化失败")
                log("⚠️ 百度语音配置已保存，但初始化失败（请检查 Key 是否正确，下次使用时会重试）")
                binding.tvBaiduStatus.text = "配置已保存，初始化失败"
                binding.tvBaiduStatus.setTextColor(0xFFFF9800.toInt())
            }
            updateBaiduStatus()
        }

        binding.btnTestBaiduConfig.setOnClickListener {
            val appId = binding.etBaiduAppId.text.toString().trim()
            val apiKey = binding.etBaiduApiKey.text.toString().trim()
            val secretKey = binding.etBaiduSecretKey.text.toString().trim()
            if (appId.isEmpty() || apiKey.isEmpty() || secretKey.isEmpty()) {
                toast("请先填写 App ID、API Key 和 Secret Key")
                return@setOnClickListener
            }
            baiduAsrManager.saveConfig(appId, apiKey, secretKey)
            binding.tvBaiduStatus.text = "正在验证配置..."
            binding.tvBaiduStatus.setTextColor(0xFFFF9800.toInt())
            log("开始验证百度配置: appId=$appId")
            Thread {
                val result = baiduAsrManager.verifyConfig(apiKey, secretKey)
                runOnUiThread {
                    when (result) {
                        is VerifyResult.Success -> {
                            baiduAsrManager.setConfigStatus(BaiduAsrManager.CONFIG_STATUS_OK)
                            binding.tvBaiduStatus.text = "✅ 配置正确"
                            binding.tvBaiduStatus.setTextColor(0xFF4CAF50.toInt())
                            toast("百度语音配置验证成功")
                            log("✅ 百度配置验证成功（状态=ok，识别时走在线）")
                        }
                        is VerifyResult.AuthError -> {
                            baiduAsrManager.setConfigStatus(BaiduAsrManager.CONFIG_STATUS_ERROR)
                            binding.tvBaiduStatus.text = "❌ ${result.message}"
                            binding.tvBaiduStatus.setTextColor(0xFFF44336.toInt())
                            toast("百度语音配置验证失败: ${result.message}")
                            log("❌ 百度配置验证失败（鉴权错误）: ${result.message}（状态=error，识别时直接走离线）")
                        }
                        is VerifyResult.NetworkError -> {
                            baiduAsrManager.setConfigStatus(BaiduAsrManager.CONFIG_STATUS_UNTESTED)
                            binding.tvBaiduStatus.text = "⚠️ ${result.message}"
                            binding.tvBaiduStatus.setTextColor(0xFFFF9800.toInt())
                            toast("网络问题，无法验证配置: ${result.message}")
                            log("⚠️ 百度配置验证失败（网络问题）: ${result.message}（状态保持未测试，等网络通了再试）")
                        }
                        else -> {
                            baiduAsrManager.setConfigStatus(BaiduAsrManager.CONFIG_STATUS_UNTESTED)
                            binding.tvBaiduStatus.text = "未知验证结果"
                            binding.tvBaiduStatus.setTextColor(0xFFFF9800.toInt())
                            log("⚠️ 百度配置验证返回未知结果")
                        }
                    }
                }
            }.start()
        }
        binding.btnImportBaiduConfig.setOnClickListener {
            try {
                filePickerLauncher.launch(arrayOf("application/json", "*/*"))
                log("打开文件选择器，选择百度配置文件")
            } catch (e: Exception) {
                log("系统文件选择器不可用：${e.message}，尝试扫描U盘")
                // ★ 必须在后台线程执行！StorageManager 遍历 USB 存储卷涉及跨进程 binder 调用，
                // 在主线程执行会卡住导致 ANR，应用被系统杀死
                Thread {
                    try {
                        scanUsbAndImport()
                    } catch (e2: Exception) {
                        log("U盘扫描异常: ${e2.javaClass.simpleName}: ${e2.message}")
                    }
                }.start()
            }
        }
    }

    /**
     * 扫描U盘并导入配置文件（系统没有文件选择器时的 fallback）
     */
    private fun scanUsbAndImport() {
        log("========== 开始 U 盘扫描（MusicFree 方案） ==========")

        if (Build.VERSION.SDK_INT >= 30) {
            val hasAllFiles = android.os.Environment.isExternalStorageManager()
            log("USB 权限检查: isExternalStorageManager=$hasAllFiles")
            if (!hasAllFiles) {
                log("⚠️ 未获得「所有文件访问权限」，U 盘读写可能失败")
            }
        }

        val candidatePaths = mutableListOf<String>()

        // ========== 第 1 层：StorageManager + 反射 getDirectory()（最可靠） ==========
        try {
            val sm = getSystemService(STORAGE_SERVICE) as android.os.storage.StorageManager
            val volumes = sm.storageVolumes
            log("StorageManager 共找到 ${volumes.size} 个存储卷")

            for ((idx, volume) in volumes.withIndex()) {
                log("  处理存储卷 [$idx] 开始...")
                val desc = try {
                    log("    获取 description...")
                    val d = volume.getDescription(this)
                    log("    description=$d")
                    d
                } catch (e: Exception) {
                    log("    获取 description 失败: ${e.message}")
                    "未知"
                }
                val isPrimary = volume.isPrimary
                val isRemovable = volume.isRemovable
                log("    isPrimary=$isPrimary, isRemovable=$isRemovable")

                log("    反射调用 getDirectory()...")
                val dir = try {
                    val m = volume.javaClass.getMethod("getDirectory")
                    val result = m.invoke(volume) as? java.io.File
                    log("    反射成功, dir=${result?.absolutePath}")
                    result
                } catch (e: Exception) {
                    log("    反射失败: ${e.message}，尝试公开 API（API 30+）")
                    try {
                        val d = volume.directory
                        log("    公开 API 成功, dir=${d?.absolutePath}")
                        d
                    } catch (e2: Exception) {
                        log("    公开 API 也失败: ${e2.message}")
                        null
                    }
                }

                val path = dir?.absolutePath
                log("    检查权限: canRead...")
                val canRead = try { dir?.canRead() ?: false } catch (e: Exception) {
                    log("    canRead() 异常: ${e.message}")
                    false
                }
                log("    检查权限: canWrite...")
                val canWrite = try { dir?.canWrite() ?: false } catch (e: Exception) {
                    log("    canWrite() 异常: ${e.message}")
                    false
                }

                log("  存储卷 [$idx]: desc=$desc, path=$path, isPrimary=$isPrimary, isRemovable=$isRemovable, canRead=$canRead, canWrite=$canWrite")

                if (isPrimary) {
                    log("    → 跳过：内置存储")
                    continue
                }
                if (dir == null) {
                    log("    → 跳过：getDirectory() 返回 null")
                    continue
                }
                if (!canRead) {
                    log("    → 跳过：无读权限 (Permission denied)")
                    continue
                }

                if (!candidatePaths.contains(dir.absolutePath)) {
                    candidatePaths.add(dir.absolutePath)
                    log("    ✅ 加入扫描列表: ${dir.absolutePath}")
                }
            }
        } catch (e: Exception) {
            log("StorageManager 枚举存储卷失败: ${e.message}")
        }

        // ========== 第 2 层：扫描 /storage 下的可读子目录 ==========
        try {
            val storageDir = java.io.File("/storage")
            if (storageDir.exists() && storageDir.isDirectory) {
                log("扫描 /storage 目录下的子目录...")
                storageDir.listFiles()?.forEach { dir ->
                    val name = dir.name
                    if (dir.isDirectory && dir.canRead() &&
                        name != "emulated" && name != "self" &&
                        !candidatePaths.contains(dir.absolutePath)) {
                        candidatePaths.add(dir.absolutePath)
                        log("  ✅ /storage 子目录: ${dir.absolutePath}")
                    }
                }
            }
        } catch (e: Exception) {
            log("扫描 /storage 失败: ${e.message}")
        }

        // ========== 第 3 层：扫描 /mnt 下的 usb 挂载点 ==========
        try {
            val mntDir = java.io.File("/mnt")
            if (mntDir.exists() && mntDir.isDirectory) {
                log("扫描 /mnt 目录下的 usb 挂载点...")
                mntDir.listFiles()?.forEach { dir ->
                    val name = dir.name.lowercase()
                    if (dir.isDirectory && dir.canRead() &&
                        (name.contains("usb") || name.contains("udisk") || name.contains("sd")) &&
                        !candidatePaths.contains(dir.absolutePath)) {
                        candidatePaths.add(dir.absolutePath)
                        log("  ✅ /mnt 子目录: ${dir.absolutePath}")
                    }
                }
            }
        } catch (e: Exception) {
            log("扫描 /mnt 失败: ${e.message}")
        }

        // ========== 第 4 层：逐个扫描路径找 .json 文件 ==========
        log("========== 开始扫描配置文件 ==========")
        log("候选路径共 ${candidatePaths.size} 个:")
        candidatePaths.forEachIndexed { index, path ->
            log("  [$index] $path")
        }

        val jsonFiles = mutableListOf<java.io.File>()

        for (path in candidatePaths) {
            val dir = java.io.File(path)
            if (!dir.exists() || !dir.isDirectory || !dir.canRead()) {
                log("  跳过（不可读）: $path")
                continue
            }

            log("----------")
            log("扫描目录: $path")

            try {
                val allFiles = dir.listFiles()
                if (allFiles == null) {
                    log("  listFiles() 返回 null")
                    continue
                }

                log("  目录下共 ${allFiles.size} 个条目:")
                allFiles.forEach { f ->
                    val type = if (f.isDirectory) "[DIR]" else "[FILE]"
                    log("    $type ${f.name} (size=${f.length()}, canRead=${f.canRead()})")
                }

                val jsonOnly = allFiles.filter { f ->
                    f.isFile && f.name.lowercase().endsWith(".json") && f.canRead()
                }

                if (jsonOnly.isNotEmpty()) {
                    log("  找到 ${jsonOnly.size} 个 .json 文件:")
                    jsonOnly.forEach { f ->
                        log("    ✅ ${f.name} (${f.length()} bytes)")
                        jsonFiles.add(f)
                    }
                } else {
                    log("  没有找到 .json 文件")
                }
            } catch (e: Exception) {
                log("  扫描异常: ${e.javaClass.simpleName}: ${e.message}")
            }
        }

        // ========== 结果处理 ==========
        log("========== U 盘扫描结果汇总 ==========")
        log("共找到 ${jsonFiles.size} 个 .json 配置文件")
        jsonFiles.forEach { f ->
            log("  ✅ ${f.absolutePath}")
        }

        when {
            jsonFiles.isEmpty() -> {
                val msg = buildString {
                    append("未在U盘找到 .json 配置文件\n")
                    append("已扫描 ${candidatePaths.size} 个路径\n")
                    candidatePaths.take(5).forEach { append("  $it\n") }
                    if (candidatePaths.size > 5) append("  ...(共${candidatePaths.size}个)\n")
                    append("请确认: 1.文件在U盘根目录 2.扩展名为.json 3.应用有U盘访问权限")
                }
                toast(msg)
                log("U盘扫描完成：未找到 .json 文件")
            }
            jsonFiles.size == 1 -> {
                log("U盘只找到一个配置文件，直接导入: ${jsonFiles[0].name}")
                importBaiduConfigFromFile(jsonFiles[0])
            }
            else -> {
                log("U盘找到 ${jsonFiles.size} 个配置文件，弹出选择对话框")
                showFileSelectDialog(jsonFiles)
            }
        }
    }

    private fun showFileSelectDialog(files: List<java.io.File>) {
        runOnUiThread {
            val fileNames = files.map { it.name }.toTypedArray()
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("选择配置文件")
                .setItems(fileNames) { _, which ->
                    importBaiduConfigFromFile(files[which])
                }
                .setNegativeButton("取消", null)
                .show()
        }
    }

    private fun importBaiduConfigFromFile(file: java.io.File) {
        try {
            val content = file.readText(Charsets.UTF_8)
            log("导入配置：读取文件成功，内容长度=${content.length}")

            val json = org.json.JSONObject(content)
            val appId = json.optString("app_id", "").trim()
            val apiKey = json.optString("api_key", "").trim()
            val secretKey = json.optString("secret_key", "").trim()

            if (appId.isEmpty() || apiKey.isEmpty() || secretKey.isEmpty()) {
                toast("配置文件格式错误，请检查 app_id/api_key/secret_key 是否完整")
                log("导入配置失败：配置不完整")
                return
            }

            runOnUiThread {
                binding.etBaiduAppId.setText(appId)
                binding.etBaiduApiKey.setText(apiKey)
                binding.etBaiduSecretKey.setText(secretKey)
                baiduAsrManager.saveConfig(appId, apiKey, secretKey)
                updateBaiduStatus()
            }

            toast("配置导入成功！请点测试连接验证配置")
            log("配置导入成功（状态=未验证，请点测试连接）")
            log("导入配置成功：appId=$appId, 来源=${file.name}")

        } catch (e: Exception) {
            toast("导入失败：${e.message}")
            log("导入配置异常：${e.message}")
        }
    }

    private fun importBaiduConfigFromUri(uri: android.net.Uri) {
        try {
            val inputStream = contentResolver.openInputStream(uri)
            if (inputStream == null) {
                toast("无法读取文件")
                log("导入配置失败：无法打开文件输入流")
                return
            }

            val content = inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            inputStream.close()
            log("导入配置：读取文件成功，内容长度=${content.length}")

            val json = org.json.JSONObject(content)
            val appId = json.optString("app_id", "").trim()
            val apiKey = json.optString("api_key", "").trim()
            val secretKey = json.optString("secret_key", "").trim()

            if (appId.isEmpty() || apiKey.isEmpty() || secretKey.isEmpty()) {
                toast("配置文件格式错误，请检查 app_id/api_key/secret_key 是否完整")
                log("导入配置失败：配置不完整")
                return
            }

            binding.etBaiduAppId.setText(appId)
            binding.etBaiduApiKey.setText(apiKey)
            binding.etBaiduSecretKey.setText(secretKey)
            baiduAsrManager.saveConfig(appId, apiKey, secretKey)
            updateBaiduStatus()

            toast("配置导入成功！请点测试连接验证配置")
            log("配置导入成功（状态=未验证，请点测试连接）")
            log("导入配置成功：appId=$appId")

        } catch (e: Exception) {
            toast("导入失败：${e.message}")
            log("导入配置异常：${e.message}")
        }
    }

    private fun updateBaiduStatus() {
        if (!baiduAsrManager.isConfigured()) {
            binding.tvBaiduStatus.text = "未配置"
            binding.tvBaiduStatus.setTextColor(0xFF9E9E9E.toInt())
        } else {
            when (baiduAsrManager.getConfigStatus()) {
                BaiduAsrManager.CONFIG_STATUS_OK -> {
                    binding.tvBaiduStatus.text = "✅ 已配置（已验证）"
                    binding.tvBaiduStatus.setTextColor(0xFF4CAF50.toInt())
                }
                BaiduAsrManager.CONFIG_STATUS_ERROR -> {
                    binding.tvBaiduStatus.text = "❌ 已配置（验证失败）"
                    binding.tvBaiduStatus.setTextColor(0xFFF44336.toInt())
                }
                else -> {
                    binding.tvBaiduStatus.text = "⚠️ 已配置（未验证，请点测试连接）"
                    binding.tvBaiduStatus.setTextColor(0xFFFF9800.toInt())
                }
            }
        }
    }

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
                binding.scrollView.post {
                    binding.scrollView.smoothScrollTo(0, binding.cardBaiduConfig.top)
                }
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
        val thresholdProgress = ((threshold / 0.001f).toInt() - 1).coerceIn(0, 99)
        binding.seekThreshold.progress = thresholdProgress
        binding.tvThresholdValue.text = String.format("%.3f", threshold)
        val gainProgress = ((gain - 1.0f) / 0.1f).toInt().coerceIn(0, 45)
        binding.seekGain.progress = gainProgress
        binding.tvGainValue.text = String.format("%.1fx", gain)
    }

    private fun updateAsrGainUI(asrGain: Float) {
        val asrGainProgress = ((asrGain - 5.0f) / 0.1f).toInt().coerceIn(0, 50)
        binding.seekAsrGain.progress = asrGainProgress
        binding.tvAsrGainValue.text = String.format("%.1fx", asrGain)
    }

    private fun fillPresetToSliders(level: Int) {
        WakeWordEngine.setSensitivity(level)
        val threshold = WakeWordEngine.getDetectionThreshold()
        val gain = WakeWordEngine.getAudioGain()
        val name = WakeWordEngine.getSensitivityName()
        updateManualUI(threshold, gain)
        updatePresetButtonState(level)
        toast("已填充「$name」预设（增益${gain}x，阈值$threshold），可微调后点击应用")
        log("预设「$name」已填充到滑块：threshold=$threshold, gain=${gain}x")
    }

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
            btn.isEnabled = true
            if (selected) {
                btn.setBackgroundColor(accentColor)
                btn.setTextColor(whiteColor)
                btn.strokeWidth = 0
            } else {
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
        updatePresetButtonState(level)
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
        updateTtsState()
        binding.tvVoiceState.text = when (VoiceAssistantService.currentState) {
            VoiceAssistantService.State.IDLE -> if (running) getString(R.string.voice_state_idle) else getString(R.string.voice_state_none)
            VoiceAssistantService.State.LISTENING -> getString(R.string.state_listening)
            VoiceAssistantService.State.PROCESSING -> getString(R.string.state_processing)
            VoiceAssistantService.State.SPEAKING -> getString(R.string.state_speaking)
        }
        val lastIntent = VoiceAssistantService.lastIntentResult
        binding.tvLastIntent.text = lastIntent

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
                ttsChecker?.shutdown()
                ttsChecker = null
                runOnUiThread { updateTtsState() }
            }
        } catch (e: Exception) {
            ttsChecked = true
            ttsAvailable = false
            android.util.Log.w("MainActivity", "TTS检测异常：${e.message}")
        }
    }

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

    private fun openTtsSettings() {
        if (ttsAvailable) {
            try {
                val intent = Intent("com.android.settings.TTS_SETTINGS")
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
            } catch (e: Exception) {
                toast(getString(R.string.toast_tts_settings_failed))
            }
        } else {
            try {
                val intent = Intent(Intent.ACTION_VIEW,
                    android.net.Uri.parse("market://search?q=讯飞语音+ TTS 离线"))
                startActivity(intent)
                toast("请在应用商店搜索并安装「讯飞语音+」，安装后在系统设置中设为默认TTS引擎")
            } catch (e: Exception) {
                try {
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

    private fun openAutoStartSettings() {
        val autoStartIntents = listOf(
            Intent().apply {
                component = android.content.ComponentName(
                    "com.miui.securitycenter",
                    "com.miui.permcenter.autostart.AutoStartManagementActivity"
                )
            },
            Intent().apply {
                component = android.content.ComponentName(
                    "com.huawei.systemmanager",
                    "com.huawei.systemmanager.optimize.process.ProtectActivity"
                )
            },
            Intent().apply {
                component = android.content.ComponentName(
                    "com.coloros.safecenter",
                    "com.coloros.safecenter.permission.startup.StartupAppListActivity"
                )
            },
            Intent().apply {
                component = android.content.ComponentName(
                    "com.iqoo.secure",
                    "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"
                )
            },
            Intent().apply {
                component = android.content.ComponentName(
                    "com.samsung.android.sm",
                    "com.samsung.android.sm.ui.ram.AutoRunActivity"
                )
            },
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:$packageName")
            },
            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
            Intent(Settings.ACTION_APPLICATION_SETTINGS),
            Intent(Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS)
        )

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
        // ★ 写文件日志：任何线程都可以调用（LogUtils 内部有锁，线程安全）
        try {
            com.xisohi.car.voiceassistant.core.LogUtils.i("MainActivity", msg)
        } catch (_: Exception) {}

        // ★ 操作 UI：必须切回主线程
        runOnUiThread {
            val time = SimpleDateFormat("HH:mm:ss", Locale.CHINA).format(Date())
            binding.tvLog.append("[$time] $msg\n")
            binding.scrollLog.post { binding.scrollLog.fullScroll(android.view.View.FOCUS_DOWN) }
        }
    }

    private fun ensurePermissions() {
        val needed = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.CALL_PHONE,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.READ_PHONE_STATE
        )
        if (Build.VERSION.SDK_INT >= 33) needed.add(Manifest.permission.POST_NOTIFICATIONS)
        if (Build.VERSION.SDK_INT >= 31) needed.add(Manifest.permission.BLUETOOTH_CONNECT)

        if (Build.VERSION.SDK_INT <= 29) {
            needed.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            needed.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }

        val missing = needed.filter { !hasPermission(it) }
        if (missing.isNotEmpty()) {
            permissionLauncher.launch(missing.toTypedArray())
        }

        if (Build.VERSION.SDK_INT >= 30) {
            checkAndRequestManageExternalStorage()
        }
    }

    private fun checkAndRequestManageExternalStorage() {
        if (Build.VERSION.SDK_INT < 30) return
        try {
            if (!android.os.Environment.isExternalStorageManager()) {
                log("未获得「所有文件访问权限」，U 盘读写可能失败，引导用户开启")
                androidx.appcompat.app.AlertDialog.Builder(this)
                    .setTitle("需要存储权限")
                    .setMessage("为了能够从 U 盘导入配置文件和导出日志，需要授予「所有文件访问权限」。\n\n点击「去设置」→ 找到本应用 → 开启「允许访问所有文件」")
                    .setPositiveButton("去设置") { _, _ ->
                        try {
                            val intent = android.content.Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                            intent.data = android.net.Uri.parse("package:$packageName")
                            startActivity(intent)
                        } catch (e: Exception) {
                            try {
                                val intent = android.content.Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                                startActivity(intent)
                            } catch (e2: Exception) {
                                toast("无法打开设置页，请手动在系统设置中开启存储权限")
                            }
                        }
                    }
                    .setNegativeButton("稍后再说", null)
                    .show()
            } else {
                log("已获得「所有文件访问权限」")
            }
        } catch (e: Exception) {
            log("检查所有文件访问权限失败: ${e.message}")
        }
    }

    private fun hasPermission(p: String): Boolean =
        ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}