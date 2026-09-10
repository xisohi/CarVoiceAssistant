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
import com.xisohi.car.voiceassistant.core.VoiceAssistantService

class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
        private const val PREFS_NAME = "voice_assistant_prefs"
        private const val KEY_AUTO_START = "auto_start_on_boot"
        private const val ACTION_ALARM_TRIGGER = "com.xisohi.car.voiceassistant.ACTION_ALARM_TRIGGER"
        private const val ALARM_INTERVAL_MS = 5 * 60 * 1000L  // 5分钟检查一次
        private const val MAX_RETRY_COUNT = 3  // 最大重试次数

        /**
         * 设置 AlarmManager 兜底闹钟：即使开机广播收不到，闹钟也会定期触发自启动检查
         * 在应用启动时调用一次即可
         */
        fun scheduleAlarmCheck(context: Context) {
            try {
                val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
                val intent = Intent(context, BootReceiver::class.java).apply {
                    action = ACTION_ALARM_TRIGGER
                }
                val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                } else {
                    PendingIntent.FLAG_UPDATE_CURRENT
                }
                val pendingIntent = PendingIntent.getBroadcast(context, 0, intent, flags)
                // 设置为不精确的重复闹钟，每5分钟触发一次
                alarmManager.setInexactRepeating(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    SystemClock.elapsedRealtime() + ALARM_INTERVAL_MS,
                    ALARM_INTERVAL_MS,
                    pendingIntent
                )
                Log.d(TAG, "AlarmManager 兜底闹钟已设置，每${ALARM_INTERVAL_MS / 60000}分钟检查一次")
            } catch (e: Exception) {
                Log.w(TAG, "设置 AlarmManager 失败: ${e.message}")
            }
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        Log.d(TAG, "收到广播: $action")

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

        // 间接触发广播只在服务未运行时才尝试启动，避免频繁触发
        if (isIndirectAction && VoiceAssistantService.isRunning) {
            return
        }

        // 检查自启开关
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val autoStart = prefs.getBoolean(KEY_AUTO_START, true)
        Log.d(TAG, "开机自启开关: $autoStart")
        if (!autoStart) return

        // 如果服务已在运行，跳过
        if (VoiceAssistantService.isRunning) {
            Log.d(TAG, "服务已在运行，跳过")
            return
        }

        // 开机广播：延迟启动，等待系统完全就绪（车机系统启动较慢）
        // 间接触发：立即启动
        val delayMs = if (isBootAction) 8000L else 0L
        Log.d(TAG, "${if (isBootAction) "开机广播" else "间接触发"}，延迟${delayMs}ms后启动服务...")

        Handler(Looper.getMainLooper()).postDelayed({
            tryStartServices(context, 0)
        }, delayMs)
    }

    private fun tryStartServices(context: Context, retryCount: Int) {
        Log.d(TAG, "尝试启动服务（第${retryCount + 1}次）...")
        try {
            // 确保使用前台服务启动（安卓10要求）
            VoiceAssistantService.start(context)
            Log.i(TAG, "已启动语音助手服务")

            // 延迟启动悬浮窗
            Handler(Looper.getMainLooper()).postDelayed({
                try {
                    FloatViewService.start(context)
                    Log.i(TAG, "已启动悬浮窗")
                } catch (e: Exception) {
                    Log.w(TAG, "启动悬浮窗失败: ${e.message}")
                }
            }, 2000)

            // 启动成功后，设置 AlarmManager 兜底闹钟（确保后续如果服务被杀死也能重启）
            scheduleAlarmCheck(context)

        } catch (e: Exception) {
            Log.e(TAG, "自启失败: ${e.message}", e)
            // 重试机制：最多重试3次，每次间隔递增
            if (retryCount < MAX_RETRY_COUNT) {
                val nextRetry = retryCount + 1
                val retryDelay = (nextRetry * 5000L)  // 5秒、10秒、15秒
                Log.d(TAG, "${retryDelay}ms后进行第${nextRetry + 1}次重试...")
                Handler(Looper.getMainLooper()).postDelayed({
                    tryStartServices(context, nextRetry)
                }, retryDelay)
            } else {
                Log.e(TAG, "已达到最大重试次数($MAX_RETRY_COUNT)，放弃本次自启")
                // 即使启动失败，也设置 AlarmManager 兜底，下次闹钟触发时再试
                scheduleAlarmCheck(context)
            }
        }
    }
}
