package com.xisohi.car.voiceassistant.core.skill

import com.xisohi.car.voiceassistant.core.ExecutionResult
import com.xisohi.car.voiceassistant.core.VoiceIntent
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 时间/帮助 Skill（无依赖，纯工具）
 *
 * 职责：查询当前时间、返回帮助信息
 * 依赖：无
 */
class TimeHelpSkill {

    fun execute(intent: VoiceIntent): ExecutionResult = when (intent.action) {
        "ask.time" -> ExecutionResult(
            true,
            "现在是" + SimpleDateFormat("HH点mm分", Locale.CHINA).format(Date())
        )
        "ask.help" -> ExecutionResult(
            true,
            "可以试试说：把音量调到五十、打开音乐、播放、暂停、下一首、导航去牛圩村、打开空调"
        )
        else -> ExecutionResult(false, "不支持的指令")
    }
}
