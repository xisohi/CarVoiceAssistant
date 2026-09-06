package com.xisohi.car.voiceassistant

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.xisohi.car.voiceassistant.core.FloatViewService
import com.xisohi.car.voiceassistant.core.VoiceAssistantService
import com.xisohi.car.voiceassistant.download.ModelManager

/**
 * 开机自启接收器：设备启动完成或用户解锁后自动启动语音助手服务。
 *
 * 支持的触发广播：
 * - BOOT_COMPLETED：设备启动完成（标准 Android）
 * - USER_PRESENT：用户解锁设备（模拟器上更可靠）
 * - QUICKBOOT_POWERON：部分厂商 ROM 的快速启动广播
 *
 * 需要模型已下载完成才会自启；未初始化时不启动。
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
        private const val PREFS_NAME = "voice_assistant_prefs"
        private const val KEY_AUTO_START = "auto_start_on_boot"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        Log.d(TAG, "收到广播: $action")

        // 只处理我们关心的广播
        val validActions = listOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_USER_PRESENT,
            "android.intent.action.QUICKBOOT_POWERON"
        )
        if (action !in validActions) return

        // 检查开机自启开关（默认开启）
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val autoStart = prefs.getBoolean(KEY_AUTO_START, true)
        Log.d(TAG, "开机自启开关: $autoStart")
        if (!autoStart) return

        // 如果服务已经在运行，跳过
        if (VoiceAssistantService.isRunning) {
            Log.d(TAG, "服务已在运行，跳过")
            return
        }

        // 仅在模型已就绪时自启
        val modelReady = ModelManager.isModelReady(context)
        Log.d(TAG, "模型就绪: $modelReady")
        if (!modelReady) {
            Log.w(TAG, "模型未就绪，不自启")
            return
        }

        try {
            // 启动语音助手前台服务
            VoiceAssistantService.start(context)
            Log.i(TAG, "已启动语音助手服务 (触发: $action)")

            // 延迟启动悬浮窗（等前台服务初始化完成）
            android.os.Handler(context.mainLooper).postDelayed({
                try {
                    FloatViewService.start(context)
                    Log.i(TAG, "已启动悬浮窗")
                } catch (e: Exception) {
                    Log.w(TAG, "启动悬浮窗失败: ${e.message}")
                }
            }, 2000)
        } catch (e: Exception) {
            Log.e(TAG, "自启失败: ${e.message}", e)
        }
    }
}
