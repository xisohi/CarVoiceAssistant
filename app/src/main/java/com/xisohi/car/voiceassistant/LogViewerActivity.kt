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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

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

        // ★ 修复3：一次最多读文件尾部 64KB（而不是整文件读——LogUtils.MAX_LOG_SIZE 单文件上限 5MB）
        private const val MAX_TAIL_BYTES = 64 * 1024L
        // 最多显示的行数：64KB 的短行日志可能有上千行，限制行数可直接减少 TextView 排版开销
        private const val MAX_LINES = 2000
    }

    private lateinit var binding: ActivityLogViewerBinding
    private val handler = Handler(Looper.getMainLooper())
    private var autoRefresh = true
    private var currentDate: String? = null

    // ★ 修复3：日志"读文件 + setText"原先每 2 秒在主线程做一次（全量读最多 5MB + 全量 setText，
    // TextView 排版会卡死 UI）。这里新建一个 IO 协程 scope：读文件在后台，setText 回主线程。
    // 本 Activity 原先没有 scope 字段，无法复用；用 SupervisorJob 便于 onDestroy 统一 cancel，
    // 不会随 Activity 销毁泄漏（这是原先裸 Handler 轮询之外新增的唯一持有物）。
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // 上一次显示的内容：内容没变就跳过 setText，避免每 2 秒做一次无意义的重新排版
    private var lastLogText: String? = null

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
                } catch (e: Throwable) {
                    // 反射 getDirectory() 失败，尝试用公开 API（API 30+）
                    try {
                        // ★ volume.directory 是 API 30+ 方法，Android 10 上会抛 NoSuchMethodError（Error 不是 Exception）
                        if (android.os.Build.VERSION.SDK_INT >= 30) {
                            volume.directory
                        } else {
                            null
                        }
                    } catch (e2: Throwable) {
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
        } catch (e: Throwable) {
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

    /**
     * 刷新日志显示
     *
     * ★ 修复3（原实现每 2 秒在主线程做一次全文件 readText + 全量 setText，单文件上限 5MB）：
     * 1. 读文件放到 [ioScope]（Dispatchers.IO），主线程只负责 setText；
     * 2. 只读文件尾部 [MAX_TAIL_BYTES]（64KB），再截到 [MAX_LINES] 行——
     *    5MB 文本即使只 setText 一次也会让 TextView 排版卡死，读尾部是最小改动且够用的方案；
     * 3. 内容与上次相同（[lastLogText]）直接跳过 setText；
     * 4. 回到主线程前校验日期快照：用户在读取期间切了日期就丢弃这次结果，
     *    避免"旧日期的内容覆盖新日期"（异步化带来的新竞态）。
     */
    private fun refreshLog() {
        val dateSnapshot = currentDate  // 主线程取快照，用于丢弃过期结果
        ioScope.launch {
            val file = resolveLogFile(dateSnapshot)
            val logText = readLogTail(file)
            val sizeKB = (file?.length() ?: 0L) / 1024
            runOnUiThread {
                // 页面已销毁 / 用户已切换日期：丢弃这次结果
                if (isFinishing || isDestroyed || dateSnapshot != currentDate) return@runOnUiThread
                if (logText != lastLogText) {
                    lastLogText = logText
                    binding.tvLog.text = logText
                    // 滚动到底部（只有内容变化时才需要滚动）
                    binding.scrollView.post {
                        binding.scrollView.fullScroll(View.FOCUS_DOWN)
                    }
                }
                binding.tvLogSize.text = "日志大小: ${sizeKB}KB"
            }
        }
    }

    /**
     * 解析当前要显示的日志文件。
     *
     * date == null 表示"今天"：走 LogUtils.getTodayLogFile()，它内部会先 flush 缓冲，
     * 否则最近不足 100 行还没落盘的日志（LogUtils 每 100 行才 flush 一次）看不到。
     */
    private fun resolveLogFile(date: String?): File? {
        if (date == null) return LogUtils.getTodayLogFile()
        val f = File(getExternalFilesDir(null), "logs/$date.txt")
        return if (f.exists()) f else null
    }

    /**
     * 只读日志文件尾部 [MAX_TAIL_BYTES] 字节并转成文本。
     *
     * 为什么不用 LogUtils.readTodayLog()/readLogByDate()：它们内部是 file.readText()（整文件），
     * 而 LogUtils 在"别人正在改"的禁改清单里，所以这里自己按字节定位到文件尾部读。
     */
    private fun readLogTail(file: File?): String {
        if (file == null || !file.exists()) return "（日志文件不存在）"
        return try {
            val length = file.length()
            val start = if (length > MAX_TAIL_BYTES) length - MAX_TAIL_BYTES else 0L
            val size = (length - start).toInt()  // 最多 64KB，toInt 安全
            val buf = ByteArray(size)
            var read = 0
            java.io.RandomAccessFile(file, "r").use { raf ->
                raf.seek(start)
                // ★ 用循环 read 而不是 readFully：日志写入线程可能同时在追加，甚至被"清空日志"截断，
                // 读到 EOF 就停，避免 EOFException 让整次刷新退化成"读取日志失败"
                while (read < size) {
                    val n = raf.read(buf, read, size - read)
                    if (n <= 0) break
                    read += n
                }
            }
            var text = String(buf, 0, read, Charsets.UTF_8)
            if (start > 0L) {
                // 从文件中间起读时首行几乎肯定是半行：丢掉第一个换行符之前的内容
                //（被切断的多字节 UTF-8 字符也在首行里，一起丢掉，不会显示成乱码；
                //  万一整段没有换行符，则只去掉开头可能出现的替换字符）
                val nl = text.indexOf('\n')
                text = if (nl >= 0) text.substring(nl + 1) else text.trimStart('\uFFFD')
            }
            takeLastLines(text)
        } catch (e: Exception) {
            "读取日志失败: ${e.message}"
        }
    }

    /**
     * 只保留最后 maxLines 行。
     * 从尾部往前数换行，避免对整段文本 split 出大量临时字符串（每 2 秒都会执行一次）。
     */
    private fun takeLastLines(text: String): String {
        var index = text.length - 1
        var lines = 1
        while (index >= 0) {
            if (text[index] == '\n') {
                lines++
                if (lines > MAX_LINES) return text.substring(index + 1)
            }
            index--
        }
        return text
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

    override fun onResume() {
        super.onResume()
        // ★ 修复3：回到前台恢复 2 秒轮询（周期不变，仍是 2000ms；onPause 里已停掉）
        handler.removeCallbacks(refreshRunnable)
        handler.postDelayed(refreshRunnable, 2000)
        refreshLog()
    }

    override fun onPause() {
        super.onPause()
        // ★ 修复3：页面不可见（如分享面板盖住、切到别的应用）时停掉轮询，
        // 避免后台每 2 秒无意义地读一次日志文件
        handler.removeCallbacks(refreshRunnable)
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(refreshRunnable)
        // ★ 修复3：取消后台读取协程，防止 Activity 销毁后仍有 runOnUiThread 回调
        ioScope.cancel()
    }
}
