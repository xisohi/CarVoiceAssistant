package com.xisohi.car.voiceassistant

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.xisohi.car.voiceassistant.core.VoiceAssistantService
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

        binding.btnDownload.setOnClickListener { startDownload() }
        binding.btnToggleService.setOnClickListener {
            if (VoiceAssistantService.isRunning) {
                VoiceAssistantService.stop(this)
            } else {
                if (ModelManager.isModelReady(this)) {
                    VoiceAssistantService.start(this)
                } else {
                    toast("请先下载离线语音包")
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        handler.post(stateRefresher)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(stateRefresher)
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }

    // ---------- UI 刷新 ----------

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

    // ---------- 首次下载 ----------

    private fun startDownload() {
        binding.btnDownload.isEnabled = false
        binding.progressDownload.isIndeterminate = true
        log("开始下载离线语音包…")
        scope.launch {
            try {
                val pack = ModelDownloader(this@MainActivity).download { p ->
                    // 下载回调在 IO 线程，必须切回主线程更新 UI
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
        val missing = needed.filter { !hasPermission(it) }
        if (missing.isNotEmpty()) {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    private fun hasPermission(p: String): Boolean =
        ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
