package com.xisohi.car.voiceassistant.core.skill

import android.content.Context
import android.media.AudioManager
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

    fun execute(intent: VoiceIntent): ExecutionResult = when (intent.action) {
        "volume.set" -> setVolume(intent.params["value"])
        "volume.up" -> adjustVolume(true)
        "volume.down" -> adjustVolume(false)
        "volume.mute" -> setMute(true)
        else -> ExecutionResult(false, "不支持的音量指令")
    }

    private fun setVolume(raw: String?): ExecutionResult {
        val value = raw?.replace("百分之", "")?.replace("%", "")?.toIntOrNull()
            ?: return ExecutionResult(false, "没听清音量数值")
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val target = (value.coerceIn(0, 100) * max / 100).coerceIn(0, max)
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
        return ExecutionResult(true, "音量已调到$value")
    }

    private fun adjustVolume(up: Boolean): ExecutionResult {
        audioManager.adjustStreamVolume(
            AudioManager.STREAM_MUSIC,
            if (up) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER,
            0
        )
        return ExecutionResult(true, if (up) "音量已调大" else "音量已调小")
    }

    private fun setMute(on: Boolean): ExecutionResult {
        audioManager.adjustStreamVolume(
            AudioManager.STREAM_MUSIC,
            AudioManager.ADJUST_MUTE,
            0
        )
        return ExecutionResult(true, "已静音")
    }
}
