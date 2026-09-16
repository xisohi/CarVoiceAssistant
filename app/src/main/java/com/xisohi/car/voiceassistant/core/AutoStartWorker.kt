package com.xisohi.car.voiceassistant.core

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.app.NotificationCompat
import com.xisohi.car.voiceassistant.MainActivity
import com.xisohi.car.voiceassistant.core.LogUtils
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * 周期性自启动检查 Worker
 *
 * 使用 WorkManager 而不是 AlarmManager 的原因：
 * - WorkManager 任务持久化，设备重启后自动恢复，不需要重新设置
 * - 不依赖开机广播（BOOT_COMPLETED），即使系统限制了开机广播也能正常工作
 * - 系统级优化，更省电，兼容 Doze 模式
 *
 * 每15分钟检查一次服务状态，如果服务没运行就自动启动。
 */
class AutoStartWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "AutoStartWorker"
        private const val WORK_NAME = "auto_start_check"
        private const val CHECK_INTERVAL_MINUTES = 15L
        private const val RESTART_NOTIFICATION_ID = 1001
        private const val RESTART_CHANNEL_ID = "auto_start_restart"
        private const val RESTART_CHANNEL_NAME = "自启动提醒" 

        /**
         * 调度周期性自启动检查任务
         * 在应用启动时调用一次即可，任务会持久化，重启后自动恢复
         */
        fun schedule(context: Context) {
            try {
                val workRequest = PeriodicWorkRequestBuilder<AutoStartWorker>(
                    CHECK_INTERVAL_MINUTES, TimeUnit.MINUTES
                ).build()

                WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                    WORK_NAME,
                    ExistingPeriodicWorkPolicy.KEEP,  // 如果已存在就保持，不重复创建
                    workRequest
                )
                LogUtils.d(TAG, "已调度周期性自启动检查，每${CHECK_INTERVAL_MINUTES}分钟一次")
            } catch (e: Exception) {
                LogUtils.w(TAG, "调度自启动检查失败: ${e.message}")
            }
        }
    }

    override suspend fun doWork(): Result {
        // 初始化文件日志（如果还没初始化）
        LogUtils.init(applicationContext)
        return try {
            LogUtils.d(TAG, "自启动检查触发，检查服务状态...")

            // 检查语音助手服务是否在运行
            if (!VoiceAssistantService.isRunning) {
                LogUtils.i(TAG, "服务未运行，尝试启动...")

                // 检查自启动开关
                val prefs = applicationContext.getSharedPreferences(
                    "voice_assistant_prefs",
                    Context.MODE_PRIVATE
                )
                val autoStart = prefs.getBoolean("auto_start_on_boot", true)
                if (!autoStart) {
                    LogUtils.d(TAG, "自启动开关已关闭，跳过")
                    return Result.success()
                }

                // 启动语音助手服务
                // 注意：Android 12+ 对后台启动前台服务限制极严，
                // WorkManager 触发的任务属于后台上下文，直接调用 startForegroundService()
                // 会抛 ForegroundServiceStartNotAllowedException。
                // 必须捕获异常并降级：发高优先级通知让用户点击拉起。
                try {
                    VoiceAssistantService.start(applicationContext)
                    LogUtils.i(TAG, "已启动语音助手服务")
                } catch (e: Exception) {
                    LogUtils.w(TAG, "后台启动前台服务被拒（Android 12+ 限制）：${e.message}")
                    // 降级：发一个高优先级通知，让用户点击拉起服务
                    sendRestartNotification()
                    return Result.success()  // 不要 retry，retry 也会失败
                }

                // 延迟2秒启动悬浮窗（等语音服务初始化完成）
                Handler(Looper.getMainLooper()).postDelayed({
                    try {
                        if (!FloatViewService.isRunning) {
                            FloatViewService.start(applicationContext)
                            LogUtils.i(TAG, "已启动悬浮窗")
                        }
                    } catch (e: Exception) {
                        LogUtils.w(TAG, "启动悬浮窗失败: ${e.message}")
                    }
                }, 2000)
            } else {
                LogUtils.d(TAG, "服务已在运行，跳过")

                // 服务在运行但悬浮窗可能被杀了，补启动悬浮窗
                if (!FloatViewService.isRunning) {
                    LogUtils.i(TAG, "悬浮窗未运行，补启动...")
                    try {
                        FloatViewService.start(applicationContext)
                    } catch (e: Exception) {
                        LogUtils.w(TAG, "后台启动悬浮窗失败：${e.message}")
                    }
                }
            }

            Result.success()
        } catch (e: Exception) {
            LogUtils.e(TAG, "自启动检查失败: ${e.message}", e)
            Result.retry()
        }
    }

    /**
     * 发送自启动提醒通知（Android 12+ 后台启动被拒时的降级方案）
     *
     * 用户点击通知后打开 MainActivity，由前台上下文启动服务。
     */
    private fun sendRestartNotification() {
        try {
            val notificationManager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            // 创建通知渠道（Android 8.0+ 需要）
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    RESTART_CHANNEL_ID,
                    RESTART_CHANNEL_NAME,
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "语音助手服务被系统杀掉后提醒用户手动启动"
                    enableVibration(true)
                }
                notificationManager.createNotificationChannel(channel)
            }

            // 点击通知打开 MainActivity（由前台上下文启动服务）
            val intent = Intent(applicationContext, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra("auto_start", true)  // 告诉 MainActivity 自动启动服务
            }

            val pendingIntent = PendingIntent.getActivity(
                applicationContext,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val notification = NotificationCompat.Builder(applicationContext, RESTART_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentTitle("语音助手需要启动")
                .setContentText("系统限制后台自启动，点击打开应用启动语音助手")
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)
                .build()

            notificationManager.notify(RESTART_NOTIFICATION_ID, notification)
            LogUtils.i(TAG, "已发送自启动提醒通知")
        } catch (e: Exception) {
            LogUtils.e(TAG, "发送自启动通知失败: ${e.message}", e)
        }
    }
}
