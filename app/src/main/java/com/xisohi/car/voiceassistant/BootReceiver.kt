package com.xisohi.car.voiceassistant

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.xisohi.car.voiceassistant.core.AutoStartWorker
import com.xisohi.car.voiceassistant.core.LogUtils

class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
        private const val PREFS_NAME = "voice_assistant_prefs"
        private const val KEY_AUTO_START = "auto_start_on_boot"
        private const val BOOT_WORK_NAME = "boot_start_service"

        /**
         * 设置 WorkManager 周期性自启动检查
         */
        fun scheduleAutoStartCheck(context: Context) {
            com.xisohi.car.voiceassistant.core.AutoStartWorker.schedule(context)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        LogUtils.init(context)
        val action = intent.action
        LogUtils.logBroadcast(action)
        LogUtils.d(TAG, "收到广播: $action")

        // 只处理开机相关广播
        val bootActions = listOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            Intent.ACTION_USER_PRESENT
        )
        if (action !in bootActions) return

        // 检查自启开关
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(KEY_AUTO_START, true)) {
            LogUtils.d(TAG, "自启开关已关闭，跳过")
            return
        }

        // 只做一件事：启动前台服务（延迟 8 秒，等系统完全就绪）
        // ★ 修复：BroadcastReceiver.onReceive() 返回后进程随时可能被杀，Handler.postDelayed(8s) 不可靠；
        // 改用 WorkManager 一次性延迟任务（持久化，进程被杀也会在延迟后可靠执行），
        // 复用 AutoStartWorker 的启动逻辑（检查服务状态 + 启动服务 + 补悬浮窗）。
        LogUtils.d(TAG, "安排 8 秒后启动前台服务（WorkManager 一次性任务）...")
        try {
            val workRequest = OneTimeWorkRequestBuilder<AutoStartWorker>()
                .setInitialDelay(8, java.util.concurrent.TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                BOOT_WORK_NAME,
                // ★ 修复：用 KEEP——开机常连续收到 BOOT_COMPLETED + USER_PRESENT，
                // REPLACE 会取消第一个的 8 秒延迟任务重新计时（8 秒变 8+X 秒）；KEEP 保留已有任务
                ExistingWorkPolicy.KEEP,
                workRequest
            )
        } catch (e: Exception) {
            LogUtils.e(TAG, "安排自启失败: ${e.message}", e)
        }
    }
}
