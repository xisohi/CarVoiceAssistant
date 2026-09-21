package com.xisohi.car.voiceassistant

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.xisohi.car.voiceassistant.core.FloatViewService
import com.xisohi.car.voiceassistant.core.LogUtils
import com.xisohi.car.voiceassistant.core.VoiceAssistantService

class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
        private const val PREFS_NAME = "voice_assistant_prefs"
        private const val KEY_AUTO_START = "auto_start_on_boot"
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

        // 开机/唤醒相关广播（直接触发）
        val bootActions = listOf(
            // 完整重启/开机
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_USER_PRESENT,
            "android.intent.action.QUICKBOOT_POWERON",
            "android.intent.action.LOCKED_BOOT_COMPLETED",
            "android.intent.action.PRE_BOOT_COMPLETED",
            "android.intent.action.REBOOT",
            "com.htc.intent.action.QUICKBOOT_POWERON",
            "android.intent.action.ACTION_BOOT_COMPLETED",
            "com.android.systemui.BOOT_COMPLETED",
            "android.intent.action.BOOT_COMPLETED_FINISHED",
            // ★ 鼎微/全志车机特有广播（从360 APK解包验证）
            "com.unisound.intent.action.ACC_ON",       // 点火开机
            "com.unisound.intent.action.DO_WAKEUP",    // 车机唤醒
            "com.unisound.intent.action.DO_SHOW",      // 车机显示
            // ★ 360 验证过的广播
            "com.unisound.intent.action.Baios_WAKEUP",  // ★ 这个才是唤醒的！
            "com.unisound.intent.action.Baios_SHUTDOWN",
            "com.unisound.intent.action.DO_SHUTDOWN",
            "com.unisound.intent.action.DO_HIDE",
            "com.unisound.intent.action.DO_SLEEP",
            // ★ 电源/点火相关
            Intent.ACTION_POWER_CONNECTED,             // 电源连接（点火）
            "android.intent.action.ACTION_POWER_CONNECTED",
            "android.intent.action.POWER_CONNECTED",
            "android.intent.action.ACTION_POWER_DISCONNECTED",
            "android.intent.action.POWER_DISCONNECTED",
            "android.intent.action.BATTERY_CHANGED",
            "android.intent.action.CHARGING",
            "android.intent.action.DISCHARGING",
            "android.os.action.POWER_SAVE_MODE_CHANGED",
            // ★ 屏幕亮/唤醒相关（点火唤醒最常用）
            "android.intent.action.SCREEN_ON",         // 屏幕亮（点火唤醒时屏幕肯定亮）
            "android.intent.action.SCREEN_OFF",
            "android.intent.action.CLOSE_SYSTEM_DIALOGS",
            // ★ 车机模式相关
            "android.app.action.ENTER_CAR_MODE",
            "android.app.action.EXIT_CAR_MODE",
            // ★ USB/U盘相关
            "android.hardware.usb.action.USB_DEVICE_ATTACHED",
            "android.hardware.usb.action.USB_DEVICE_DETACHED",
            "android.hardware.usb.action.USB_ACCESSORY_ATTACHED"
        )
        val isBootAction = action in bootActions
        if (!isBootAction) return

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

        // 开机/唤醒广播：延迟启动，等待系统完全就绪（车机系统启动较慢）
        val delayMs = when {
            // 屏幕亮/唤醒不用等太久，2秒就够了
            action == "android.intent.action.SCREEN_ON" ||
            action == "android.intent.action.USER_PRESENT" ||
            action == "com.unisound.intent.action.DO_WAKEUP" ||
            action == "com.unisound.intent.action.ACC_ON" ||
            action == Intent.ACTION_POWER_CONNECTED -> 2000L
            // 冷启动要等久一点
            else -> 8000L
        }
        LogUtils.d(TAG, "广播 $action，延迟${delayMs}ms后启动服务...")

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
