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
            Intent.ACTION_USER_PRESENT,
            Intent.ACTION_SHUTDOWN
        )
        if (action !in bootActions) return

        // 检查自启开关
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(KEY_AUTO_START, true)) {
            LogUtils.d(TAG, "自启开关已关闭，跳过")
            return
        }

        // 关机广播不启动服务
        if (action == Intent.ACTION_SHUTDOWN) {
            LogUtils.d(TAG, "收到关机广播，不启动服务")
            return
        }

        // 只做一件事：启动前台服务（延迟 8 秒，等系统完全就绪）
        LogUtils.d(TAG, "延迟 8000ms 后启动前台服务...")
        Handler(Looper.getMainLooper()).postDelayed({
            try {
                VoiceAssistantService.start(context)
                LogUtils.i(TAG, "已启动语音助手服务")
                scheduleAutoStartCheck(context)
            } catch (e: Exception) {
                LogUtils.e(TAG, "自启失败: ${e.message}", e)
            }
        }, 8000L)
    }
}
