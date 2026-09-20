package com.xisohi.car.voiceassistant.core.monitor

import android.content.Context
import android.media.AudioManager
import android.util.Log
import android.view.KeyEvent

/**
 * 音频焦点管理器
 *
 * 职责：
 * - 识别时降低媒体音量到0（专注听用户说话，提高识别率）
 * - 识别完成后恢复媒体音量到原始值
 * - 保存/恢复原始音量，避免重复保存
 * - 支持语音控制音量（调大/调小/设置到指定值）
 *
 * 车机场景：
 * - 识别时音乐/导航声音会干扰语音识别，需要临时静音
 * - 识别完成后立即恢复，不影响用户正常使用
 *
 * 双保险策略：
 * 1. 先尝试用音频焦点（标准 API，音乐 App 会自动降低音量）
 * 2. 再尝试直接改 STREAM_MUSIC 音量（有些车机允许，有些不允许）
 * 3. 音量控制：先试 adjustStreamVolume，再试 setStreamVolume，再试发送音量按键
 */
class AudioFocusManager(private val context: Context) {
    /** 保存的原始媒体音量（-1表示未保存） */
    private var originalMediaVolume: Int = -1

    /** 标记是否已经降低了媒体音量 */
    private var isMediaVolumeMuted = false

    /** 音频焦点申请结果 */
    private var audioFocusGranted = false

    /**
     * 降低媒体音量到 0（音乐、导航等），专注听用户说话，提高识别率。
     * 第一次调用时保存原始音量，后续调用不会重复保存。
     *
     * 双保险：先申请音频焦点，再尝试直接改音量。
     */
    fun muteMediaVolume() {
        try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

            // === 第一步：申请音频焦点（标准 API，音乐 App 会自动降低音量） ===
            if (!audioFocusGranted) {
                val result = audioManager.requestAudioFocus(
                    null,
                    AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
                )
                audioFocusGranted = (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
                if (audioFocusGranted) {
                    Log.d(TAG, "✅ 音频焦点申请成功，音乐 App 应自动降低音量")
                } else {
                    Log.w(TAG, "⚠️ 音频焦点申请失败，尝试直接改音量")
                }
            }

            // === 第二步：直接改 STREAM_MUSIC 音量（有些车机允许，有些不允许） ===
            if (originalMediaVolume < 0) {
                originalMediaVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
            }
            if (!isMediaVolumeMuted) {
                try {
                    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
                    isMediaVolumeMuted = true
                    Log.d(TAG, "✅ 已直接降低媒体音量到 0（原始音量: $originalMediaVolume）")
                } catch (e: SecurityException) {
                    Log.w(TAG, "❌ 车机系统禁止直接改音量: ${e.message}")
                    Log.d(TAG, "💡 但音频焦点应该已经生效，音乐 App 会自动降低音量")
                    isMediaVolumeMuted = true // 标记为已静音，避免重复尝试
                }
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
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

            // === 第一步：放弃音频焦点 ===
            if (audioFocusGranted) {
                audioManager.abandonAudioFocus(null)
                audioFocusGranted = false
                Log.d(TAG, "✅ 已放弃音频焦点，音乐 App 应恢复正常音量")
            }

            // === 第二步：恢复 STREAM_MUSIC 音量 ===
            if (isMediaVolumeMuted && originalMediaVolume >= 0) {
                try {
                    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, originalMediaVolume, 0)
                    Log.d(TAG, "✅ 已直接恢复媒体音量到: $originalMediaVolume")
                } catch (e: SecurityException) {
                    Log.w(TAG, "⚠️ 车机系统禁止直接改音量: ${e.message}")
                }
                isMediaVolumeMuted = false
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
        audioFocusGranted = false
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
     * 三级策略：
     * 1. 先试 setStreamVolume（标准 API）
     * 2. 失败再试 adjustStreamVolume（调整方式，权限要求可能更低）
     * 3. 再失败就发送音量按键（模拟用户按音量键）
     */
    fun setVolume(volume: Int) {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val maxVolume = getMaxVolume()
        val targetVolume = volume.coerceIn(0, maxVolume)
        val currentVolume = getCurrentVolume()

        Log.d(TAG, "尝试设置音量: $targetVolume (当前: $currentVolume, 最大: $maxVolume)")

        // === 第一级：setStreamVolume ===
        try {
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, targetVolume, 0)
            Log.d(TAG, "✅ setStreamVolume 成功，音量已设置为: $targetVolume")
            return
        } catch (e: SecurityException) {
            Log.w(TAG, "⚠️ setStreamVolume 被禁止: ${e.message}")
        } catch (e: Exception) {
            Log.w(TAG, "⚠️ setStreamVolume 失败: ${e.message}")
        }

        // === 第二级：adjustStreamVolume（多次调整到目标值） ===
        try {
            val diff = targetVolume - currentVolume
            if (diff != 0) {
                val direction = if (diff > 0) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER
                val steps = kotlin.math.abs(diff)
                Log.d(TAG, "尝试 adjustStreamVolume，方向: ${if (direction == AudioManager.ADJUST_RAISE) "增大" else "减小"}, 步数: $steps")
                repeat(steps) {
                    audioManager.adjustStreamVolume(
                        AudioManager.STREAM_MUSIC,
                        direction,
                        AudioManager.FLAG_SHOW_UI
                    )
                }
                Log.d(TAG, "✅ adjustStreamVolume 完成")
                return
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "⚠️ adjustStreamVolume 被禁止: ${e.message}")
        } catch (e: Exception) {
            Log.w(TAG, "⚠️ adjustStreamVolume 失败: ${e.message}")
        }

        // === 第三级：发送音量按键（模拟用户按音量键） ===
        try {
            val diff = targetVolume - currentVolume
            if (diff != 0) {
                val keyCode = if (diff > 0) KeyEvent.KEYCODE_VOLUME_UP else KeyEvent.KEYCODE_VOLUME_DOWN
                val steps = kotlin.math.abs(diff)
                Log.d(TAG, "尝试发送音量按键，按键码: $keyCode, 次数: $steps")
                repeat(steps) {
                    val down = KeyEvent(KeyEvent.ACTION_DOWN, keyCode)
                    val up = KeyEvent(KeyEvent.ACTION_UP, keyCode)
                    audioManager.dispatchMediaKeyEvent(down)
                    audioManager.dispatchMediaKeyEvent(up)
                }
                Log.d(TAG, "✅ 音量按键发送完成")
                return
            }
        } catch (e: Exception) {
            Log.w(TAG, "⚠️ 音量按键发送失败: ${e.message}")
        }

        Log.e(TAG, "❌ 所有音量控制方式都失败了！车机系统限制太严格")
        Log.e(TAG, "💡 建议用户直接用车机物理按键或方向盘按键调音量")
    }

    /**
     * 音量调大（+1格）
     */
    fun volumeUp() {
        val current = getCurrentVolume()
        setVolume(current + 1)
    }

    /**
     * 音量调小（-1格）
     */
    fun volumeDown() {
        val current = getCurrentVolume()
        setVolume(current - 1)
    }

    companion object {
        private const val TAG = "AudioFocusManager"
    }
}
