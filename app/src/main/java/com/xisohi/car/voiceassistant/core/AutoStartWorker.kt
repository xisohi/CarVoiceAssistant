package com.xisohi.car.voiceassistant.core

import android.content.Context
import android.os.Handler
import android.os.Looper
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
        private const val WORK_NAME = "auto_start_check"
        private const val CHECK_INTERVAL_MINUTES = 15L

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
                android.util.Log.d("AutoStartWorker", "已调度周期性自启动检查，每${CHECK_INTERVAL_MINUTES}分钟一次")
            } catch (e: Exception) {
                android.util.Log.w("AutoStartWorker", "调度自启动检查失败: ${e.message}")
            }
        }
    }

    override suspend fun doWork(): Result {
        return try {
            android.util.Log.d("AutoStartWorker", "自启动检查触发，检查服务状态...")

            // 检查语音助手服务是否在运行
            if (!VoiceAssistantService.isRunning) {
                android.util.Log.i("AutoStartWorker", "服务未运行，尝试启动...")

                // 检查自启动开关
                val prefs = applicationContext.getSharedPreferences(
                    "voice_assistant_prefs",
                    Context.MODE_PRIVATE
                )
                val autoStart = prefs.getBoolean("auto_start_on_boot", true)
                if (!autoStart) {
                    android.util.Log.d("AutoStartWorker", "自启动开关已关闭，跳过")
                    return Result.success()
                }

                // 启动语音助手服务
                VoiceAssistantService.start(applicationContext)
                android.util.Log.i("AutoStartWorker", "已启动语音助手服务")

                // 延迟2秒启动悬浮窗（等语音服务初始化完成）
                Handler(Looper.getMainLooper()).postDelayed({
                    try {
                        if (!FloatViewService.isRunning) {
                            FloatViewService.start(applicationContext)
                            android.util.Log.i("AutoStartWorker", "已启动悬浮窗")
                        }
                    } catch (e: Exception) {
                        android.util.Log.w("AutoStartWorker", "启动悬浮窗失败: ${e.message}")
                    }
                }, 2000)
            } else {
                android.util.Log.d("AutoStartWorker", "服务已在运行，跳过")

                // 服务在运行但悬浮窗可能被杀了，补启动悬浮窗
                if (!FloatViewService.isRunning) {
                    android.util.Log.i("AutoStartWorker", "悬浮窗未运行，补启动...")
                    FloatViewService.start(applicationContext)
                }
            }

            Result.success()
        } catch (e: Exception) {
            android.util.Log.e("AutoStartWorker", "自启动检查失败: ${e.message}", e)
            Result.retry()
        }
    }
}
