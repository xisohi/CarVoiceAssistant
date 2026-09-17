package com.xisohi.car.voiceassistant.core.skill

import com.xisohi.car.voiceassistant.core.ExecutionResult
import com.xisohi.car.voiceassistant.core.VoiceIntent

/**
 * 车控 Skill（厂商 SDK 扩展点）
 *
 * 职责：空调开关/温度、车窗开关
 *
 * 注意：真正的车控需要接入车机厂商 SDK/广播，
 * 通过 [CarControlProvider] 接口扩展，默认实现为占位提示。
 *
 * 依赖：无（通过 CarControlProvider 接口扩展）
 */
class CarControlSkill {

    /** 车控扩展接口：接入厂商 SDK 时实现并注入 */
    interface CarControlProvider {
        fun setClimate(on: Boolean): Boolean = false
        fun setTemperature(degree: Int): Boolean = false
        fun openWindow(position: String): Boolean = false
        fun closeWindow(position: String): Boolean = false
    }

    var provider: CarControlProvider? = null

    fun execute(intent: VoiceIntent): ExecutionResult = when (intent.action) {
        "climate.on" -> climateOn()
        "climate.off" -> climateOff()
        "climate.temp" -> climateTemp(intent.params["value"] ?: "")
        "window.open" -> window(true, intent.params["position"] ?: "全部")
        "window.close" -> window(false, intent.params["position"] ?: "全部")
        else -> ExecutionResult(false, "不支持的车控指令")
    }

    private fun climateOn(): ExecutionResult {
        val p = provider
        return if (p != null && p.setClimate(true)) {
            ExecutionResult(true, "空调已开启")
        } else {
            ExecutionResult(
                false,
                "空调控制需要接入车机厂商 SDK，请实现 CarControlProvider 接口"
            )
        }
    }

    private fun climateOff(): ExecutionResult {
        val p = provider
        return if (p != null && p.setClimate(false)) {
            ExecutionResult(true, "空调已关闭")
        } else {
            ExecutionResult(
                false,
                "空调控制需要接入车机厂商 SDK，请实现 CarControlProvider 接口"
            )
        }
    }

    private fun climateTemp(raw: String): ExecutionResult {
        val degree = raw.replace("度", "").toIntOrNull()
            ?: return ExecutionResult(false, "没听清温度数值")
        val p = provider
        return if (p != null && p.setTemperature(degree)) {
            ExecutionResult(true, "空调已调到${degree}度")
        } else {
            ExecutionResult(
                false,
                "空调温度控制需要接入车机厂商 SDK，请实现 CarControlProvider 接口"
            )
        }
    }

    private fun window(open: Boolean, position: String): ExecutionResult {
        val p = provider
        return if (p != null) {
            val ok = if (open) p.openWindow(position) else p.closeWindow(position)
            if (ok) ExecutionResult(true, "已${if (open) "打开" else "关闭"}${position}车窗")
            else ExecutionResult(false, "车窗控制失败")
        } else {
            ExecutionResult(
                false,
                "车窗控制需要接入车机厂商 SDK，请实现 CarControlProvider 接口"
            )
        }
    }
}
