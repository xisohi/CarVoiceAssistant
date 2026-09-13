package com.xisohi.car.voiceassistant

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.xisohi.car.voiceassistant.core.FloatViewService
import com.xisohi.car.voiceassistant.core.LogUtils
import com.xisohi.car.voiceassistant.core.VoiceAssistantService

class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
        private const val PREFS_NAME = "voice_assistant_prefs"
        private const val KEY_AUTO_START = "auto_start_on_boot"
        private const val ACTION_ALARM_TRIGGER = "com.xisohi.car.voiceassistant.ACTION_ALARM_TRIGGER"
        private const val ALARM_INTERVAL_MS = 15 * 60 * 1000L  // 15分钟检查一次（Android 6.0+ Doze模式下最小间隔约9分钟，15分钟符合系统限制）
        private const val MAX_RETRY_COUNT = 3  // 最大重试次数

        /**
         * 设置 WorkManager 周期性自启动检查
         *
         * 使用 WorkManager 而不是 AlarmManager 的原因：
         * - WorkManager 任务持久化，设备重启后自动恢复，不需要重新设置
         * - 不依赖开机广播（BOOT_COMPLETED），即使系统限制了开机广播也能正常工作
         * - 系统级优化，更省电，兼容 Doze 模式
         *
         * 在应用启动时调用一次即可，任务会持久化，重启后自动恢复
         */
        fun scheduleAutoStartCheck(context: Context) {
            com.xisohi.car.voiceassistant.core.AutoStartWorker.schedule(context)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        // 初始化文件日志（如果还没初始化）
        LogUtils.init(context)
        val action = intent.action
        // 记录收到的广播到文件（即使应用被杀，重新打开后也能看到）
        LogUtils.logBroadcast(action)
        LogUtils.d(TAG, "收到广播: $action")

        // 开机相关广播（直接触发）
        val bootActions = listOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_USER_PRESENT,
            "android.intent.action.QUICKBOOT_POWERON",
            "android.intent.action.LOCKED_BOOT_COMPLETED",
            "android.intent.action.REBOOT",
            "com.htc.intent.action.QUICKBOOT_POWERON",
            "android.intent.action.ACTION_BOOT_COMPLETED",
            "com.android.systemui.BOOT_COMPLETED",
            "android.intent.action.BOOT_COMPLETED_FINISHED"
        )
        // 间接触发广播（系统事件，作为备用触发机制）
        val indirectActions = listOf(
            "android.net.conn.CONNECTIVITY_CHANGE",
            "android.intent.action.MEDIA_MOUNTED",
            "android.bluetooth.adapter.action.STATE_CHANGED",
            "android.hardware.usb.action.USB_STATE",
            "android.intent.action.HEADSET_PLUG",
            ACTION_ALARM_TRIGGER  // AlarmManager 兜底触发
        )
        val isBootAction = action in bootActions
        val isIndirectAction = action in indirectActions
        if (!isBootAction && !isIndirectAction) return

        // 间接触发广播：语音服务和悬浮窗都在运行时才跳过，避免悬浮窗被杀后补不回来
        if (isIndirectAction && VoiceAssistantService.isRunning && FloatViewService.isRunning) {
            return
        }

        // 检查自启开关
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val autoStart = prefs.getBoolean(KEY_AUTO_START, true)
        LogUtils.d(TAG, "开机自启开关: $autoStart")
        if (!autoStart) return

        // 如果服务已在运行，跳过
        if (VoiceAssistantService.isRunning) {
            LogUtils.d(TAG, "服务已在运行，检查悬浮窗...")
            if (!FloatViewService.isRunning) {
                try {
                    FloatViewService.start(context)
                    LogUtils.i(TAG, "补启动悬浮窗")
                } catch (e: Exception) {
                    LogUtils.w(TAG, "补启动悬浮窗失败: ${e.message}")
                }
            }
            return
        }

        // 开机广播：延迟启动，等待系统完全就绪（车机系统启动较慢）
        // 间接触发：立即启动
        val delayMs = if (isBootAction) 8000L else 0L
        LogUtils.d(TAG, "${if (isBootAction) "开机广播" else "间接触发"}，延迟${delayMs}ms后启动服务...")

        Handler(Looper.getMainLooper()).postDelayed({
            tryStartServices(context, 0)
        }, delayMs)
    }

    private fun tryStartServices(context: Context, retryCount: Int) {
        LogUtils.d(TAG, "尝试启动服务（第${retryCount + 1}次）...")
        try {
            // 确保使用前台服务启动（安卓10要求）
            VoiceAssistantService.start(context)
            LogUtils.i(TAG, "已启动语音助手服务")

            // 延迟启动悬浮窗
            Handler(Looper.getMainLooper()).postDelayed({
                try {
                    FloatViewService.start(context)
                    LogUtils.i(TAG, "已启动悬浮窗")
                } catch (e: Exception) {
                    LogUtils.w(TAG, "启动悬浮窗失败: ${e.message}")
                }
            }, 2000)

            // 启动成功后，设置 WorkManager 周期性检查（确保后续如果服务被杀死也能重启）
            scheduleAutoStartCheck(context)

        } catch (e: Exception) {
            LogUtils.e(TAG, "自启失败: ${e.message}", e)
            // 重试机制：最多重试3次，每次间隔递增
            if (retryCount < MAX_RETRY_COUNT) {
                val nextRetry = retryCount + 1
                val retryDelay = (nextRetry * 5000L)  // 5秒、10秒、15秒
                LogUtils.d(TAG, "${retryDelay}ms后进行第${nextRetry + 1}次重试...")
                Handler(Looper.getMainLooper()).postDelayed({
                    tryStartServices(context, nextRetry)
                }, retryDelay)
            } else {
                LogUtils.e(TAG, "已达到最大重试次数($MAX_RETRY_COUNT)，放弃本次自启")
                // 即使启动失败，也设置 WorkManager 兜底，下次任务触发时再试
                scheduleAutoStartCheck(context)
            }
        }
    }
}
