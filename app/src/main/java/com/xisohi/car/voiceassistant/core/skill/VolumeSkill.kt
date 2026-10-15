package com.xisohi.car.voiceassistant.core.skill

import android.content.Context
import android.media.AudioManager
import android.view.KeyEvent
import com.xisohi.car.voiceassistant.core.ExecutionResult
import com.xisohi.car.voiceassistant.core.NumberUtils
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
        // ★ 支持汉字数字："百分之五十"/"五十" → 50（识别结果几乎都是汉字数字）
        val cleaned = raw?.replace("百分之", "")?.replace("%", "")
            ?: return ExecutionResult(false, "没听清音量数值")
        val value = cleaned.toIntOrNull() ?: NumberUtils.chineseToInt(cleaned)
            ?: return ExecutionResult(false, "没听清音量数值")
        
        var success = false
        for (streamType in STREAMS_TO_TRY) {
            try {
                val max = audioManager.getStreamMaxVolume(streamType)
                if (max <= 0) continue
                val target = (value.coerceIn(0, 100) * max / 100).coerceIn(0, max)
                audioManager.setStreamVolume(streamType, target, 0)
                // ★ 修复5：回读校验。原实现只要 setStreamVolume 不抛异常就置 success=true，
                // 但部分车机 ROM 对第三方 App 改音量是"静默忽略"（不报错也不生效），
                // 于是语音回答"音量已调到X"而实际没变（假成功）。改为设置后 getStreamVolume
                // 回读，与目标值一致才算这个流真的生效。
                val actual = audioManager.getStreamVolume(streamType)
                if (actual == target) {
                    android.util.Log.d("VolumeSkill", "成功设置 stream=$streamType 音量=$target（回读一致）")
                    success = true
                    break  // ★ 保留原行为：第一个真正生效的流即停止，避免 5 个流音量全被改
                }
                android.util.Log.w("VolumeSkill", "stream=$streamType 设置无效：目标=$target，回读=$actual，继续尝试下一个流")
            } catch (e: Exception) {
                android.util.Log.w("VolumeSkill", "stream=$streamType 设置失败: ${e.message}")
            }
        }
        
        // 如果直接设置未生效（不是"没抛异常"，而是"回读没变"），降级模拟音量键
        if (!success) {
            android.util.Log.w("VolumeSkill", "直接设置无效，降级模拟音量键")
            // 模拟前先记录，用于回读判断是否真的动了
            val beforeFallback = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
            simulateVolumeKeys(value)
            val afterFallback = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
            if (afterFallback != beforeFallback) {
                android.util.Log.d("VolumeSkill", "模拟音量键生效: $beforeFallback → $afterFallback")
                success = true
            }
        }
        
        // ★ 修复5：所有方式都没真正改变音量时如实报失败，不再无条件 return true 谎报成功
        return if (success) {
            ExecutionResult(true, "音量已调到$value")
        } else {
            ExecutionResult(false, "音量调整失败，车机可能禁止应用改音量")
        }
    }

    private fun adjustVolume(up: Boolean): ExecutionResult {
        var success = false
        for (streamType in STREAMS_TO_TRY) {
            try {
                // ★ 修复5：回读判断音量是否真的变了（adjustStreamVolume 不抛异常 ≠ 生效）
                val before = audioManager.getStreamVolume(streamType)
                audioManager.adjustStreamVolume(
                    streamType,
                    if (up) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER,
                    0
                )
                val after = audioManager.getStreamVolume(streamType)
                if (after != before) {
                    android.util.Log.d("VolumeSkill", "成功调整 stream=$streamType: $before → $after")
                    success = true
                    break  // ★ 保留原行为：第一个真正生效的流即停止
                }
                android.util.Log.w("VolumeSkill", "stream=$streamType 调整无效（回读仍是 $after），继续尝试下一个流")
            } catch (e: Exception) {
                android.util.Log.w("VolumeSkill", "stream=$streamType 调整失败: ${e.message}")
            }
        }
        
        // 直接调整全部无效时降级模拟音量键；同样回读确认（该 API 走 MediaSession，多数情况是空操作）
        if (!success) {
            val beforeFallback = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
            simulateVolumeKey(up)
            val afterFallback = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
            if (afterFallback != beforeFallback) {
                android.util.Log.d("VolumeSkill", "模拟音量键生效: $beforeFallback → $afterFallback")
                success = true
            }
        }
        
        return if (success) {
            ExecutionResult(true, if (up) "音量已调大" else "音量已调小")
        } else {
            ExecutionResult(false, "音量调整失败，车机可能禁止应用改音量")
        }
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
                break  // ★ 修复：第一个成功的流即停止；success 用于向用户如实报告结果
            } catch (e: Exception) {
                android.util.Log.w("VolumeSkill", "stream=$streamType 静音失败: ${e.message}")
            }
        }
        
        return if (success) {
            ExecutionResult(true, if (on) "已静音" else "已取消静音")
        } else {
            ExecutionResult(false, if (on) "静音失败，车机不支持" else "取消静音失败，车机不支持")
        }
    }
    
    /**
     * 模拟系统音量键（车机屏蔽直接设置时用这个）
     *
     * ★ 实测认知：dispatchMediaKeyEvent(KEYCODE_VOLUME_UP/DOWN) 走的是 MediaSession（媒体键）路径，
     * 不是 AudioService 的音量键处理路径，没有活跃 MediaSession 时就是空操作。
     * 所以它只是"聊胜于无"的兜底，调用方必须回读音量确认是否真的生效（见 setVolume/adjustVolume）。
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
            if (max <= 0) return
            val current = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
            val target = (targetPercent.coerceIn(0, 100) * max / 100).coerceIn(0, max)
            val diff = target - current
            
            // ★ 修复5：diff == 0 直接返回。当前音量已经等于目标值，不需要按键；
            // 原实现会继续走完流程（steps=0，一个键都不发），调用方又无法区分
            // "已经到位"和"按键无效"，最后打出"音量调整失败"的误导日志。
            if (diff == 0) {
                android.util.Log.d("VolumeSkill", "模拟音量键：当前音量已等于目标($current)，无需按键")
                return
            }
            
            android.util.Log.d("VolumeSkill", "模拟音量键: current=$current, target=$target, diff=$diff")
            
            // ★ 审计 #14 修复：限制最大按键次数（20 次）——
            // 修复前 diff 无上限（如 max=100、目标 100% 时按键 100 次），慢且可能触发系统限流。
            val steps = kotlin.math.min(kotlin.math.abs(diff), 20)
            repeat(steps) {
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
