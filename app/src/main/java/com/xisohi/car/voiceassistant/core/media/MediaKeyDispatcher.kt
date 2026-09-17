package com.xisohi.car.voiceassistant.core.media

import android.content.Context
import android.content.Intent
import android.media.AudioManager

/**
 * 媒体按键分发器
 *
 * 职责：发送媒体按键（播放/暂停/下一首/上一首）
 *
 * 三层策略（按优先级）：
 * 1. [通用] AudioManager.dispatchMediaKeyEvent（系统级媒体按键，最可靠）
 * 2. [通用] 发送给目标播放器（指定包名的广播）
 * 3. [通用] 逐个指定常见播放器包名发送广播（兜底）
 *
 * 依赖：Context + AudioManager
 */
class MediaKeyDispatcher(private val context: Context) {

    private val audioManager =
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    /**
     * 发送媒体按键
     *
     * @param keyCode 按键码（如 KeyEvent.KEYCODE_MEDIA_PLAY）
     * @param targetPackage 目标播放器包名（null=系统默认）
     * @param allPlayerPackages 所有播放器包名列表（用于兜底广播）
     */
    fun dispatch(
        keyCode: Int,
        targetPackage: String? = null,
        allPlayerPackages: List<String> = emptyList()
    ) {
        val down = android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, keyCode)
        val up = android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, keyCode)

        // 1. 系统级媒体按键（发送给当前活跃的 MediaSession，最可靠）
        try {
            audioManager.dispatchMediaKeyEvent(down)
            audioManager.dispatchMediaKeyEvent(up)
            android.util.Log.d("MediaKeyDispatcher", "已发送系统级媒体按键: $keyCode, 目标: ${targetPackage ?: "系统默认"}")
        } catch (e: Exception) {
            android.util.Log.w("MediaKeyDispatcher", "dispatchMediaKeyEvent 失败: ${e.message}")
        }

        // 2. 发送给目标播放器（指定包名的广播）
        targetPackage?.let { pkg ->
            try {
                val downIntent = Intent(Intent.ACTION_MEDIA_BUTTON)
                    .setPackage(pkg)
                    .putExtra(Intent.EXTRA_KEY_EVENT, down)
                val upIntent = Intent(Intent.ACTION_MEDIA_BUTTON)
                    .setPackage(pkg)
                    .putExtra(Intent.EXTRA_KEY_EVENT, up)
                context.sendBroadcast(downIntent)
                context.sendBroadcast(upIntent)
                android.util.Log.d("MediaKeyDispatcher", "已发送媒体按键广播给: $pkg")
            } catch (e: Exception) {
                android.util.Log.w("MediaKeyDispatcher", "发送广播给 $pkg 失败: ${e.message}")
            }
        }

        // 3. 兜底：逐个指定所有播放器包名发送广播（确保至少有一个响应）
        for (pkg in allPlayerPackages) {
            if (pkg == targetPackage) continue // 已经发过了，跳过
            try {
                val downIntent = Intent(Intent.ACTION_MEDIA_BUTTON)
                    .setPackage(pkg)
                    .putExtra(Intent.EXTRA_KEY_EVENT, down)
                val upIntent = Intent(Intent.ACTION_MEDIA_BUTTON)
                    .setPackage(pkg)
                    .putExtra(Intent.EXTRA_KEY_EVENT, up)
                context.sendBroadcast(downIntent)
                context.sendBroadcast(upIntent)
            } catch (_: Exception) {
            }
        }
    }
}
