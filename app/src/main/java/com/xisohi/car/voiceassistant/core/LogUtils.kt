package com.xisohi.car.voiceassistant.core

import android.content.Context
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.locks.ReentrantLock

/**
 * 文件日志工具类
 *
 * 所有日志同时输出到 logcat 和文件，即使应用被杀，日志也会保存在文件中。
 * 重新打开应用后可以查看历史日志（包括车机启动时收到的广播记录）。
 *
 * 文件存储位置：/sdcard/Android/data/com.xisohi.car.voiceassistant/files/logs/
 * 按日期分割：2026-09-13.txt
 * 自动清理：保留最近7天的日志
 */
object LogUtils {
    private const val LOG_DIR = "logs"
    private const val KEEP_DAYS = 7
    private const val MAX_LOG_SIZE = 5 * 1024 * 1024L // 单个日志文件最大5MB

    private val lock = ReentrantLock()
    private var context: Context? = null
    private var logDir: File? = null

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())
    private val fileDateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())

    /**
     * 初始化日志工具，在 Application.onCreate() 或 MainActivity.onCreate() 中调用
     */
    fun init(ctx: Context) {
        context = ctx.applicationContext
        logDir = File(ctx.getExternalFilesDir(null), LOG_DIR)
        if (!logDir!!.exists()) {
            logDir!!.mkdirs()
        }
        // 清理旧日志
        cleanOldLogs()
        d("LogUtils", "文件日志系统初始化完成，日志目录: ${logDir!!.absolutePath}")
    }

    /**
     * 调试日志
     */
    @JvmStatic
    fun d(tag: String, message: String) {
        android.util.Log.d(tag, message)
        writeToFile("D", tag, message)
    }

    /**
     * 信息日志
     */
    @JvmStatic
    fun i(tag: String, message: String) {
        android.util.Log.i(tag, message)
        writeToFile("I", tag, message)
    }

    /**
     * 警告日志
     */
    @JvmStatic
    fun w(tag: String, message: String) {
        android.util.Log.w(tag, message)
        writeToFile("W", tag, message)
    }

    /**
     * 错误日志
     */
    @JvmStatic
    fun e(tag: String, message: String, throwable: Throwable? = null) {
        android.util.Log.e(tag, message, throwable)
        writeToFile("E", tag, message)
        if (throwable != null) {
            val sw = java.io.StringWriter()
            throwable.printStackTrace(PrintWriter(sw))
            writeToFile("E", tag, sw.toString())
        }
    }

    /**
     * 记录收到的广播（专门用于 BootReceiver）
     */
    fun logBroadcast(action: String?, extras: String = "") {
        val msg = if (extras.isNotEmpty()) {
            "收到广播: $action, extras: $extras"
        } else {
            "收到广播: $action"
        }
        i("BootReceiver", msg)
    }

    /**
     * 读取今天的日志内容
     */
    fun readTodayLog(): String {
        return readLog(getTodayFile())
    }

    /**
     * 读取指定日期的日志内容
     */
    fun readLogByDate(date: String): String {
        val file = File(logDir, "$date.txt")
        return readLog(file)
    }

    /**
     * 读取所有可用的日志日期列表
     */
    fun getAvailableDates(): List<String> {
        if (logDir == null || !logDir!!.exists()) return emptyList()
        return logDir!!.listFiles { _, name -> name.endsWith(".txt") }
            ?.map { it.nameWithoutExtension }
            ?.sortedDescending()
            ?: emptyList()
    }

    /**
     * 获取今天的日志文件路径（用于分享/导出）
     */
    fun getTodayLogFile(): File? {
        val file = getTodayFile()
        return if (file.exists()) file else null
    }

    /**
     * 清空所有日志
     */
    fun clearAllLogs() {
        lock.lock()
        try {
            if (logDir != null && logDir!!.exists()) {
                logDir!!.listFiles()?.forEach { it.delete() }
            }
        } finally {
            lock.unlock()
        }
    }

    // ==================== 内部方法 ====================

    private fun writeToFile(level: String, tag: String, message: String) {
        if (logDir == null) return
        lock.lock()
        try {
            val file = getTodayFile()
            // 如果文件太大，重命名为备份文件，创建新文件
            if (file.exists() && file.length() > MAX_LOG_SIZE) {
                val backup = File(logDir, "${file.nameWithoutExtension}_backup.txt")
                if (backup.exists()) backup.delete()
                file.renameTo(backup)
            }
            val timestamp = dateFormat.format(Date())
            val logLine = "$timestamp [$level] $tag: $message\n"
            FileWriter(file, true).use { it.write(logLine) }
        } catch (e: Exception) {
            android.util.Log.w("LogUtils", "写入日志文件失败: ${e.message}")
        } finally {
            lock.unlock()
        }
    }

    private fun getTodayFile(): File {
        val dateStr = fileDateFormat.format(Date())
        return File(logDir, "$dateStr.txt")
    }

    private fun readLog(file: File): String {
        if (!file.exists()) return "（日志文件不存在）"
        return try {
            file.readText()
        } catch (e: Exception) {
            "读取日志失败: ${e.message}"
        }
    }

    private fun cleanOldLogs() {
        if (logDir == null || !logDir!!.exists()) return
        val cutoff = System.currentTimeMillis() - KEEP_DAYS * 24 * 60 * 60 * 1000L
        logDir!!.listFiles()?.forEach { file ->
            if (file.lastModified() < cutoff) {
                file.delete()
            }
        }
    }
}
