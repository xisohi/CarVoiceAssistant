package com.xisohi.car.voiceassistant

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.xisohi.car.voiceassistant.core.LogUtils
import com.xisohi.car.voiceassistant.databinding.ActivityLogViewerBinding
import java.io.File

/**
 * 日志查看页面
 *
 * 查看文件日志，包括：
 * - 今天的日志
 * - 历史日志（按日期切换）
 * - 车机启动时收到的广播记录
 * - 服务启动/停止记录
 * - 错误信息
 */
class LogViewerActivity : AppCompatActivity() {

    companion object {
        private const val REQUEST_CODE_SAVE_FILE = 1001
    }

    private lateinit var binding: ActivityLogViewerBinding
    private val handler = Handler(Looper.getMainLooper())
    private var autoRefresh = true
    private var currentDate: String? = null

    private val refreshRunnable = object : Runnable {
        override fun run() {
            if (autoRefresh && currentDate == null) {
                refreshLog()
            }
            handler.postDelayed(this, 2000) // 每2秒自动刷新
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLogViewerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 初始化 LogUtils
        LogUtils.init(this)

        setupToolbar()
        setupDateSpinner()
        setupButtons()
        refreshLog()

        // 开始自动刷新
        handler.postDelayed(refreshRunnable, 2000)
    }

    private fun setupToolbar() {
        binding.toolbar.setNavigationOnClickListener { finish() }
    }

    private fun setupDateSpinner() {
        val dates = LogUtils.getAvailableDates().toMutableList()
        if (dates.isEmpty()) {
            dates.add("今天")
        } else if (!dates.contains("今天")) {
            dates.add(0, "今天")
        }

        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, dates)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spinnerDate.adapter = adapter

        binding.spinnerDate.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val selected = binding.spinnerDate.selectedItem.toString()
                if (selected == "今天") {
                    currentDate = null
                    autoRefresh = true
                } else {
                    currentDate = selected
                    autoRefresh = false
                }
                refreshLog()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun setupButtons() {
        // 刷新按钮
        binding.btnRefresh.setOnClickListener {
            refreshLog()
            Toast.makeText(this, "已刷新", Toast.LENGTH_SHORT).show()
        }

        // 清空按钮
        binding.btnClear.setOnClickListener {
            LogUtils.clearAllLogs()
            refreshLog()
            setupDateSpinner() // 刷新日期列表
            Toast.makeText(this, "日志已清空", Toast.LENGTH_SHORT).show()
        }

        // 导出到U盘按钮
        binding.btnExport.setOnClickListener {
            exportToUsb()
        }

        // 分享按钮
        binding.btnShare.setOnClickListener {
            shareLog()
        }
    }

    /**
     * 导出日志到U盘
     * 优先使用 StorageManager 获取存储卷（Android 7.0+），
     * 其次扫描常见的U盘挂载路径，最后用系统文件选择器
     */
    private fun exportToUsb() {
        val logFile = if (currentDate == null) {
            LogUtils.getTodayLogFile()
        } else {
            val f = java.io.File(getExternalFilesDir(null), "logs/$currentDate.txt")
            if (f.exists()) f else null
        }

        if (logFile == null || !logFile.exists()) {
            Toast.makeText(this, "日志文件不存在", Toast.LENGTH_SHORT).show()
            return
        }

        var usbDir: java.io.File? = null
        var usbName = ""

        // 方法1：使用 StorageManager 获取存储卷（Android 7.0+，最可靠）
        try {
            val storageManager = getSystemService(STORAGE_SERVICE) as android.os.storage.StorageManager
            val volumes = storageManager.storageVolumes
            for (volume in volumes) {
                // 跳过内置存储
                if (volume.isPrimary) continue
                // 只考虑可移除的存储（U盘/SD卡）
                if (!volume.isRemovable) continue
                // 获取挂载路径（用反射兼容 Android 10 及以下，getDirectory() 是 API 30 才有的）
                val dirPath = try {
                    // 优先用反射调用 getPath()（所有版本都有）
                    val getPathMethod = volume.javaClass.getMethod("getPath")
                    getPathMethod.invoke(volume) as? String
                } catch (e: Exception) {
                    // 反射失败，尝试用 getDirectory()（API 30+）
                    try {
                        volume.directory?.absolutePath
                    } catch (e2: Exception) {
                        null
                    }
                } ?: continue
                val dir = java.io.File(dirPath)
                if (dir.exists() && dir.isDirectory) {
                    // 尝试创建临时文件测试可写性
                    val testFile = java.io.File(dir, ".test_write_${System.currentTimeMillis()}")
                    val canWrite = try {
                        testFile.createNewFile()
                        testFile.delete()
                        true
                    } catch (_: Exception) {
                        false
                    }
                    if (canWrite) {
                        usbDir = dir
                        usbName = volume.getDescription(this) ?: "U盘"
                        LogUtils.i("LogViewer", "StorageManager找到U盘: $usbName, path=${dir.absolutePath}")
                        break
                    }
                }
            }
        } catch (e: Exception) {
            LogUtils.w("LogViewer", "StorageManager获取存储卷失败: ${e.message}")
        }

        // 方法2：扫描常见的U盘挂载路径（车机可能用自定义路径）
        if (usbDir == null) {
            val usbPaths = listOf(
                "/storage/usb0", "/storage/usb1", "/storage/usb2",
                "/storage/usbdisk", "/storage/UDisk", "/storage/udisk",
                "/storage/usb_storage", "/storage/usbhost",
                "/mnt/usb", "/mnt/usb0", "/mnt/usb1", "/mnt/udisk", "/mnt/usbdisk",
                "/mnt/usb_storage", "/mnt/usbhost",
                "/storage/external_storage", "/storage/extSdCard",
                "/storage/sdcard1", "/storage/sdcard2",
                "/mnt/external_sd", "/mnt/ext_sd"
            )
            for (path in usbPaths) {
                val dir = java.io.File(path)
                if (dir.exists() && dir.isDirectory) {
                    // 确认不是内置存储
                    val canonicalPath = try { dir.canonicalPath } catch (_: Exception) { path }
                    if (canonicalPath.contains("/sdcard") ||
                        canonicalPath.contains("/emulated") ||
                        canonicalPath.contains("/self")) {
                        continue
                    }
                    // 测试可写性
                    val testFile = java.io.File(dir, ".test_write_${System.currentTimeMillis()}")
                    val canWrite = try {
                        testFile.createNewFile()
                        testFile.delete()
                        true
                    } catch (_: Exception) {
                        false
                    }
                    if (canWrite) {
                        usbDir = dir
                        usbName = "U盘($path)"
                        LogUtils.i("LogViewer", "路径扫描找到U盘: $path")
                        break
                    }
                }
            }
        }

        if (usbDir != null) {
            // 找到U盘，直接复制
            try {
                val destFile = java.io.File(usbDir, "CarVoiceAssistant_${logFile.name}")
                logFile.copyTo(destFile, overwrite = true)
                Toast.makeText(this, "已导出到$usbName: ${destFile.name}", Toast.LENGTH_LONG).show()
                LogUtils.i("LogViewer", "日志已导出到U盘: ${destFile.absolutePath}")
            } catch (e: Exception) {
                Toast.makeText(this, "导出失败: ${e.message}", Toast.LENGTH_SHORT).show()
                LogUtils.e("LogViewer", "导出到U盘失败: ${e.message}", e)
            }
        } else {
            // 没找到U盘，用系统文件选择器
            Toast.makeText(this, "未检测到U盘，请选择保存位置", Toast.LENGTH_SHORT).show()
            val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TITLE, "CarVoiceAssistant_${logFile.name}")
                addCategory(Intent.CATEGORY_OPENABLE)
            }
            try {
                startActivityForResult(intent, REQUEST_CODE_SAVE_FILE)
            } catch (e: Exception) {
                Toast.makeText(this, "无法打开文件选择器: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_CODE_SAVE_FILE && resultCode == RESULT_OK) {
            data?.data?.let { uri ->
                try {
                    val logFile = if (currentDate == null) {
                        LogUtils.getTodayLogFile()
                    } else {
                        val f = java.io.File(getExternalFilesDir(null), "logs/$currentDate.txt")
                        if (f.exists()) f else null
                    }
                    logFile?.let { file ->
                        contentResolver.openOutputStream(uri)?.use { output ->
                            file.inputStream().use { input ->
                                input.copyTo(output)
                            }
                        }
                        Toast.makeText(this, "日志已保存", Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    Toast.makeText(this, "保存失败: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun refreshLog() {
        val logText = if (currentDate == null) {
            LogUtils.readTodayLog()
        } else {
            LogUtils.readLogByDate(currentDate!!)
        }

        binding.tvLog.text = logText

        // 滚动到底部
        binding.scrollView.post {
            binding.scrollView.fullScroll(View.FOCUS_DOWN)
        }

        // 更新日志大小
        val file = if (currentDate == null) {
            LogUtils.getTodayLogFile()
        } else {
            val f = File(getExternalFilesDir(null), "logs/$currentDate.txt")
            if (f.exists()) f else null
        }
        val sizeKB = file?.length()?.div(1024) ?: 0
        binding.tvLogSize.text = "日志大小: ${sizeKB}KB"
    }

    private fun shareLog() {
        val file = if (currentDate == null) {
            LogUtils.getTodayLogFile()
        } else {
            val f = File(getExternalFilesDir(null), "logs/$currentDate.txt")
            if (f.exists()) f else null
        }

        if (file == null || !file.exists()) {
            Toast.makeText(this, "日志文件不存在", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            val uri: Uri = FileProvider.getUriForFile(
                this,
                "$packageName.fileprovider",
                file
            )
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "语音助手日志 - ${file.name}")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "分享日志"))
        } catch (e: Exception) {
            // 如果 FileProvider 不可用，直接用文件路径
            Toast.makeText(this, "分享失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(refreshRunnable)
    }
}
