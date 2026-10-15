package com.xisohi.car.voiceassistant.core.media

import android.content.Context
import android.media.AudioManager

/**
 * 媒体按键分发器
 *
 * 职责：发送媒体按键（播放/暂停/下一首/上一首）
 *
 * 只走一层策略：
 * - AudioManager.dispatchMediaKeyEvent（系统级媒体按键，由系统发给当前活跃的 MediaSession）
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
     * @param targetPackage 目标播放器包名（仅用于日志；实际由系统按活跃 MediaSession 决定）
     */
    fun dispatch(
        keyCode: Int,
        targetPackage: String? = null
    ) {
        val down = android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, keyCode)
        val up = android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, keyCode)

        // 系统级媒体按键（发送给当前活跃的 MediaSession，最可靠）
        try {
            audioManager.dispatchMediaKeyEvent(down)
            audioManager.dispatchMediaKeyEvent(up)
            android.util.Log.d("MediaKeyDispatcher", "已发送系统级媒体按键: $keyCode, 目标: ${targetPackage ?: "系统默认"}")
        } catch (e: Exception) {
            android.util.Log.w("MediaKeyDispatcher", "dispatchMediaKeyEvent 失败: ${e.message}")
        }

        // ★ 修复4：删掉"逐个播放器包名 sendBroadcast(ACTION_MEDIA_BUTTON)"的兜底。
        // 原兜底会对除目标外的每一个播放器包（MusicPlayerManager 里共 12 个）单独发广播，
        // 等于把媒体键塞给所有未被系统选中的播放器 —— 车机上会出现多个播放器同时响应
        // （典型现象：说"下一首"时后台 App 一起切歌）。而媒体键本就该由系统按活跃
        // MediaSession 路由（上面的 dispatchMediaKeyEvent），广播兜底收益低、副作用大。
    }
}
