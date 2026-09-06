package com.xisohi.car.voiceassistant

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.xisohi.car.voiceassistant.core.VoiceAssistantService
import com.xisohi.car.voiceassistant.download.ModelManager

/**
 * 开机自启接收器：设备启动完成后自动启动语音助手服务。
 * 需要模型已下载完成才会自启；未初始化时不启动（避免服务启动后无法工作）。
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        // 仅在模型已就绪时自启
        if (!ModelManager.isModelReady(context)) return
        try {
            VoiceAssistantService.start(context)
        } catch (_: Exception) {
        }
    }
}
