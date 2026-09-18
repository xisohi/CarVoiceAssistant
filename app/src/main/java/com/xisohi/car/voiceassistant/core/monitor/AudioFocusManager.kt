package com.xisohi.car.voiceassistant.core.monitor

import android.content.Context
import android.media.AudioManager
import android.util.Log

/**
 * 音频焦点管理器
 *
 * 职责：
 * - 识别时降低媒体音量到0（专注听用户说话，提高识别率）
 * - 识别完成后恢复媒体音量到原始值
 * - 保存/恢复原始音量，避免重复保存
 *
 * 车机场景：
 * - 识别时音乐/导航声音会干扰语音识别，需要临时静音
 * - 识别完成后立即恢复，不影响用户正常使用
 *
 * 使用方式：
 * ```
 * val audioFocus = AudioFocusManager(context)
 * audioFocus.muteMediaVolume()   // 识别前降低音量
 * audioFocus.restoreMediaVolume() // 识别后恢复音量
 * ```
 */
class AudioFocusManager(private val context: Context) {
    /** 保存的原始媒体音量（-1表示未保存） */
    private var originalMediaVolume: Int = -1

    /** 标记是否已经降低了媒体音量 */
    private var isMediaVolumeMuted = false

    /**
     * 降低媒体音量到 0（音乐、导航等），专注听用户说话，提高识别率。
     * 第一次调用时保存原始音量，后续调用不会重复保存。
     */
    fun muteMediaVolume() {
        try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            if (originalMediaVolume < 0) {
                originalMediaVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
            }
            if (!isMediaVolumeMuted) {
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
                isMediaVolumeMuted = true
                Log.d(TAG, "已降低媒体音量到 0（原始音量: $originalMediaVolume），专注听用户说话")
            }
        } catch (e: Exception) {
            Log.w(TAG, "降低媒体音量失败: ${e.message}")
        }
    }

    /**
     * 恢复媒体音量到唤醒前的原始值。
     * 识别完成后或 TTS 播报前调用。
     */
    fun restoreMediaVolume() {
        try {
            if (isMediaVolumeMuted && originalMediaVolume >= 0) {
                val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, originalMediaVolume, 0)
                isMediaVolumeMuted = false
                Log.d(TAG, "已恢复媒体音量到: $originalMediaVolume")
                originalMediaVolume = -1
            }
        } catch (e: Exception) {
            Log.w(TAG, "恢复媒体音量失败: ${e.message}")
        }
    }

    /**
     * 当前是否处于静音状态（媒体音量已被降低）。
     */
    fun isMuted(): Boolean = isMediaVolumeMuted

    /**
     * 重置状态（强制清空保存的原始音量）。
     * 在异常恢复或服务销毁时调用，避免下次 muteMediaVolume() 不更新音量。
     */
    fun reset() {
        originalMediaVolume = -1
        isMediaVolumeMuted = false
    }

    /**
     * 获取当前媒体音量。
     */
    fun getCurrentVolume(): Int {
        return try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        } catch (e: Exception) {
            Log.w(TAG, "获取媒体音量失败: ${e.message}")
            0
        }
    }

    /**
     * 获取媒体音量最大值。
     */
    fun getMaxVolume(): Int {
        return try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        } catch (e: Exception) {
            Log.w(TAG, "获取最大音量失败: ${e.message}")
            15
        }
    }

    /**
     * 设置媒体音量到指定值。
     */
    fun setVolume(volume: Int) {
        try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, volume, 0)
        } catch (e: Exception) {
            Log.w(TAG, "设置媒体音量失败: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "AudioFocusManager"
    }
}
