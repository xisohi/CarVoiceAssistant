package com.xisohi.car.voiceassistant.core.skill

import android.content.Context
import android.media.AudioManager
import android.view.KeyEvent
import com.xisohi.car.voiceassistant.core.ExecutionResult
import com.xisohi.car.voiceassistant.core.VoiceIntent

/**
 * 音量控制 Skill
 *
 * 职责：音量设置、调大/调小、静音
 * 依赖：Context + AudioManager
 */
class VolumeSkill(private val context: Context) {

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    // 车机可能用的音频流类型，按优先级尝试
    private val STREAMS_TO_TRY = intArrayOf(
        AudioManager.STREAM_MUSIC,
        AudioManager.STREAM_SYSTEM,
        AudioManager.STREAM_RING,
        AudioManager.STREAM_NOTIFICATION,
        AudioManager.STREAM_ALARM
    )

    fun execute(intent: VoiceIntent): ExecutionResult = when (intent.action) {
        "volume.set" -> setVolume(intent.params["value"])
        "volume.up" -> adjustVolume(true)
        "volume.down" -> adjustVolume(false)
        "volume.mute" -> setMute(true)
        "volume.unmute" -> setMute(false)
        else -> ExecutionResult(false, "不支持的音量指令")
    }

    private fun setVolume(raw: String?): ExecutionResult {
        val value = raw?.replace("百分之", "")?.replace("%", "")?.toIntOrNull()
            ?: return ExecutionResult(false, "没听清音量数值")
        
        var success = false
        for (streamType in STREAMS_TO_TRY) {
            try {
                val max = audioManager.getStreamMaxVolume(streamType)
                if (max <= 0) continue
                val target = (value.coerceIn(0, 100) * max / 100).coerceIn(0, max)
                audioManager.setStreamVolume(streamType, target, 0)
                android.util.Log.d("VolumeSkill", "成功设置 stream=$streamType 音量=$target")
                success = true
            } catch (e: Exception) {
                android.util.Log.w("VolumeSkill", "stream=$streamType 设置失败: ${e.message}")
            }
        }
        
        // 如果直接设置失败，模拟音量键
        if (!success) {
            android.util.Log.w("VolumeSkill", "直接设置失败，尝试模拟音量键")
            simulateVolumeKeys(value)
        }
        
        return ExecutionResult(true, "音量已调到$value")
    }

    private fun adjustVolume(up: Boolean): ExecutionResult {
        var success = false
        for (streamType in STREAMS_TO_TRY) {
            try {
                audioManager.adjustStreamVolume(
                    streamType,
                    if (up) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER,
                    0
                )
                android.util.Log.d("VolumeSkill", "成功调整 stream=$streamType")
                success = true
            } catch (e: Exception) {
                android.util.Log.w("VolumeSkill", "stream=$streamType 调整失败: ${e.message}")
            }
        }
        
        // 如果直接调整失败，模拟音量键
        if (!success) {
            simulateVolumeKey(up)
        }
        
        return ExecutionResult(true, if (up) "音量已调大" else "音量已调小")
    }

    private fun setMute(on: Boolean): ExecutionResult {
        var success = false
        for (streamType in STREAMS_TO_TRY) {
            try {
                audioManager.adjustStreamVolume(
                    streamType,
                    if (on) AudioManager.ADJUST_MUTE else AudioManager.ADJUST_UNMUTE,
                    0
                )
                android.util.Log.d("VolumeSkill", "成功设置 stream=$streamType 静音=$on")
                success = true
            } catch (e: Exception) {
                android.util.Log.w("VolumeSkill", "stream=$streamType 静音失败: ${e.message}")
            }
        }
        
        return ExecutionResult(true, if (on) "已静音" else "已取消静音")
    }
    
    /**
     * 模拟系统音量键（车机屏蔽直接设置时用这个）
     */
    private fun simulateVolumeKey(up: Boolean) {
        try {
            val keyCode = if (up) KeyEvent.KEYCODE_VOLUME_UP else KeyEvent.KEYCODE_VOLUME_DOWN
            val downEvent = KeyEvent(KeyEvent.ACTION_DOWN, keyCode)
            val upEvent = KeyEvent(KeyEvent.ACTION_UP, keyCode)
            audioManager.dispatchMediaKeyEvent(downEvent)
            audioManager.dispatchMediaKeyEvent(upEvent)
            android.util.Log.d("VolumeSkill", "模拟音量键: $keyCode")
        } catch (e: Exception) {
            android.util.Log.w("VolumeSkill", "模拟音量键失败: ${e.message}")
        }
    }
    
    /**
     * 模拟音量键调到指定值
     */
    private fun simulateVolumeKeys(targetPercent: Int) {
        try {
            val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val current = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
            val target = targetPercent * max / 100
            val diff = target - current
            
            android.util.Log.d("VolumeSkill", "模拟音量键: current=$current, target=$target, diff=$diff")
            
            repeat(kotlin.math.abs(diff)) {
                if (diff > 0) {
                    simulateVolumeKey(true)
                } else {
                    simulateVolumeKey(false)
                }
            }
        } catch (e: Exception) {
            android.util.Log.w("VolumeSkill", "模拟音量键调到指定值失败: ${e.message}")
        }
    }
}
