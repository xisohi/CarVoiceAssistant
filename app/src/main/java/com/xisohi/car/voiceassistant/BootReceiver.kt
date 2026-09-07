package com.xisohi.car.voiceassistant

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.xisohi.car.voiceassistant.core.FloatViewService
import com.xisohi.car.voiceassistant.core.VoiceAssistantService
import com.xisohi.car.voiceassistant.download.ModelManager

class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
        private const val PREFS_NAME = "voice_assistant_prefs"
        private const val KEY_AUTO_START = "auto_start_on_boot"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        Log.d(TAG, "收到广播: $action")

        val validActions = listOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_USER_PRESENT,
            "android.intent.action.QUICKBOOT_POWERON"
        )
        if (action !in validActions) return

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

        // 延迟启动，等待系统完全就绪（增加成功率）
        Handler(Looper.getMainLooper()).postDelayed({
            tryStartServices(context)
        }, 5000) // 延迟5秒
    }

    private fun tryStartServices(context: Context) {
        Log.d(TAG, "尝试启动服务...")
        // 不强制检查模型是否就绪，让服务自己去处理
        try {
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
        } catch (e: Exception) {
            Log.e(TAG, "自启失败: ${e.message}", e)
            // 可以尝试再重试一次
            Handler(Looper.getMainLooper()).postDelayed({
                try {
                    VoiceAssistantService.start(context)
                    Log.i(TAG, "重试启动语音助手成功")
                } catch (e2: Exception) {
                    Log.e(TAG, "重试仍然失败: ${e2.message}")
                }
            }, 10000)
        }
    }
}