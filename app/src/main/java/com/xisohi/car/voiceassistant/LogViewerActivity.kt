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
            LogUtils.i("LogViewer", "StorageManager 共找到 ${volumes.size} 个存储卷")
            for (volume in volumes) {
                // ★ 关键：优先用反射调用 getDirectory()（MusicFree 方案）
                // getDirectory() 返回的 File 对象在 Android 10 上有读写权限（Google 兼容性后门）
                // 而 getPath() 返回的字符串路径在 Android 10 上对普通 App 没有权限
                // volume.directory 是 API 30+ 才有的公开属性，Android 10 上是 null
                val dir = try {
                    val getDirectoryMethod = volume.javaClass.getMethod("getDirectory")
                    getDirectoryMethod.invoke(volume) as? java.io.File
                } catch (e: Exception) {
                    // 反射 getDirectory() 失败，尝试用公开 API（API 30+）
                    try {
                        volume.directory
                    } catch (e2: Exception) {
                        null
                    }
                }
                val volDesc = try { volume.getDescription(this) } catch (_: Exception) { "未知" }
                val canRead = dir?.canRead() ?: false
                val initialCanWrite = dir?.canWrite() ?: false
                LogUtils.i("LogViewer", "存储卷: desc=$volDesc, path=${dir?.absolutePath}, isPrimary=${volume.isPrimary}, isRemovable=${volume.isRemovable}, canRead=$canRead, canWrite=$initialCanWrite")

                // 跳过内置存储
                if (volume.isPrimary) {
                    LogUtils.i("LogViewer", "  跳过：内置存储")
                    continue
                }
                if (dir == null) {
                    LogUtils.i("LogViewer", "  跳过：getDirectory() 返回 null")
                    continue
                }
                if (!dir.exists() || !dir.isDirectory) {
                    LogUtils.i("LogViewer", "  跳过：路径不存在或不是目录")
                    continue
                }
                if (!dir.canRead()) {
                    LogUtils.i("LogViewer", "  跳过：无读权限 (Permission denied)")
                    continue
                }
                // 测试可写性（创建普通文件，不创建隐藏文件，某些文件系统不支持隐藏文件）
                val testFile = java.io.File(dir, "test_write_${System.currentTimeMillis()}.tmp")
                val canWrite = try {
                    testFile.createNewFile()
                    testFile.writeText("test")  // 写几个字节测试真正可写
                    testFile.delete()
                    true
                } catch (e: Exception) {
                    LogUtils.i("LogViewer", "  可写性测试失败: ${e.message}")
                    dir.canWrite()  // 退化为用 canWrite() 判断
                }
                if (!canWrite) {
                    LogUtils.i("LogViewer", "  跳过：不可写")
                    continue
                }
                usbDir = dir
                usbName = volDesc
                LogUtils.i("LogViewer", "✅ StorageManager找到U盘: $usbName, path=${dir.absolutePath}")
                break
            }
        } catch (e: Exception) {
            LogUtils.w("LogViewer", "StorageManager获取存储卷失败: ${e.message}")
        }

        // 方法2：扫描常见的U盘挂载路径（车机可能用自定义路径）
        if (usbDir == null) {
            LogUtils.i("LogViewer", "StorageManager 未找到U盘，开始扫描常见挂载路径...")
            val usbPaths = listOf(
                // 标准 Android 路径
                "/storage/usb0", "/storage/usb1", "/storage/usb2", "/storage/usb3",
                "/storage/usbdisk", "/storage/UDisk", "/storage/udisk",
                "/storage/usb_storage", "/storage/usbhost", "/storage/UsbDrive",
                // mnt 路径
                "/mnt/usb", "/mnt/usb0", "/mnt/usb1", "/mnt/usb2", "/mnt/udisk", "/mnt/usbdisk",
                "/mnt/usb_storage", "/mnt/usbhost", "/mnt/UsbDrive",
                // 车机常见路径（全志/鼎微/方易通等方案）
                "/storage/external_storage", "/storage/extSdCard",
                "/storage/sdcard1", "/storage/sdcard2", "/storage/sdcard3",
                "/mnt/external_sd", "/mnt/ext_sd", "/mnt/sdcard",
                // 其他常见路径
                "/storage/udisk0", "/storage/udisk1",
                "/mnt/udisk0", "/mnt/udisk1",
                "/storage/USBdisk", "/storage/USBdisk1",
                "/mnt/USBdisk", "/mnt/USBdisk1",
                "/storage/usb_disk", "/mnt/usb_disk"
            )
            var scannedCount = 0
            for (path in usbPaths) {
                val dir = java.io.File(path)
                if (!dir.exists() || !dir.isDirectory) {
                    continue
                }
                scannedCount++
                // 确认不是内置存储
                val canonicalPath = try { dir.canonicalPath } catch (_: Exception) { path }
                if (canonicalPath.contains("/sdcard") ||
                    canonicalPath.contains("/emulated") ||
                    canonicalPath.contains("/self")) {
                    LogUtils.i("LogViewer", "  跳过（内置存储）: $path")
                    continue
                }
                val canRead = dir.canRead()
                LogUtils.i("LogViewer", "  扫描路径: $path (exists=${dir.exists()}, canRead=$canRead)")
                if (!canRead) {
                    LogUtils.i("LogViewer", "    → 跳过：无读权限")
                    continue
                }
                // 测试可写性（创建普通文件，不创建隐藏文件）
                val testFile = java.io.File(dir, "test_write_${System.currentTimeMillis()}.tmp")
                val canWrite = try {
                    testFile.createNewFile()
                    testFile.writeText("test")
                    testFile.delete()
                    true
                } catch (e: Exception) {
                    LogUtils.i("LogViewer", "    → 可写性测试失败: ${e.message}")
                    dir.canWrite()
                }
                if (canWrite) {
                    usbDir = dir
                    usbName = "U盘($path)"
                    LogUtils.i("LogViewer", "    ✅ 路径扫描找到U盘: $path")
                    break
                } else {
                    LogUtils.i("LogViewer", "    → 跳过：不可写")
                }
            }
            LogUtils.i("LogViewer", "路径扫描完成，共扫描到 $scannedCount 个存在的路径")
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
            // 没找到U盘，先尝试导出到内置存储的 Download 目录（车机可能没有文件选择器）
            LogUtils.w("LogViewer", "未找到U盘，尝试导出到应用内置存储目录")
            try {
                // 使用应用自己的外部存储目录（Android 10+ 分区存储限制，不能写公共 Download 目录）
                val appExternalDir = getExternalFilesDir(null)
                val exportDir = java.io.File(appExternalDir, "export")
                if (!exportDir.exists()) {
                    exportDir.mkdirs()
                    LogUtils.i("LogViewer", "创建导出目录: ${exportDir.absolutePath}")
                }
                val destFile = java.io.File(exportDir, "CarVoiceAssistant_${logFile.name}")
                LogUtils.i("LogViewer", "开始复制日志到: ${destFile.absolutePath}")
                logFile.copyTo(destFile, overwrite = true)
                LogUtils.i("LogViewer", "✅ 日志已导出到内置存储: ${destFile.absolutePath} (${destFile.length()} bytes)")
                Toast.makeText(
                    this,
                    "未检测到U盘，已导出到: ${destFile.absolutePath}",
                    Toast.LENGTH_LONG
                ).show()
                LogUtils.i("LogViewer", "未检测到U盘，已导出到应用目录: ${destFile.absolutePath}")
            } catch (e: Exception) {
                // 内置存储也失败，尝试用系统文件选择器
                LogUtils.w("LogViewer", "导出到内置存储失败: ${e.message}")
                Toast.makeText(this, "未检测到U盘，请选择保存位置", Toast.LENGTH_SHORT).show()
                val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TITLE, "CarVoiceAssistant_${logFile.name}")
                    addCategory(Intent.CATEGORY_OPENABLE)
                }
                try {
                    startActivityForResult(intent, REQUEST_CODE_SAVE_FILE)
                } catch (e2: Exception) {
                    Toast.makeText(this, "导出失败: ${e2.message}", Toast.LENGTH_SHORT).show()
                }
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
