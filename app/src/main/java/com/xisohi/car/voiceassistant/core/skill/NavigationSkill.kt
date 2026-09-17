package com.xisohi.car.voiceassistant.core.skill

import android.content.Context
import com.xisohi.car.voiceassistant.core.ExecutionResult
import com.xisohi.car.voiceassistant.core.VoiceIntent
import com.xisohi.car.voiceassistant.core.nav.AmapAutoLauncher
import com.xisohi.car.voiceassistant.core.nav.AmapMobileLauncher
import com.xisohi.car.voiceassistant.core.nav.BaiduLauncher
import com.xisohi.car.voiceassistant.core.nav.GeoLauncher

/**
 * 导航调度 Skill
 *
 * 职责：按优先级依次尝试各个导航应用
 *
 * 优先级：
 * 1. 高德车机版（AmapAutoLauncher）- 原生支持多结果语音选择
 * 2. 高德手机版（AmapMobileLauncher）
 * 3. 百度地图汽车版（BaiduLauncher）
 * 4. 通用 geo: 协议（GeoLauncher）- 兜底
 *
 * 依赖：Context + 4个 Launcher
 */
class NavigationSkill(private val context: Context) {

    // 按优先级排序的 Launcher 列表
    private val launchers = listOf(
        AmapAutoLauncher(),    // 1. 高德车机版（优先）
        AmapMobileLauncher(),  // 2. 高德手机版
        BaiduLauncher(),       // 3. 百度地图汽车版
        GeoLauncher()          // 4. 通用 geo: 协议（兜底）
    )

    fun execute(intent: VoiceIntent): ExecutionResult {
        val dest = intent.params["dest"] ?: ""
        return navigate(dest)
    }

    /**
     * 拉起导航
     *
     * @param dest 目的地关键字
     * @return ExecutionResult（handled=true 表示成功拉起）
     */
    fun navigate(dest: String): ExecutionResult {
        if (dest.isBlank()) {
            return ExecutionResult(false, "请告诉我目的地")
        }

        // 按优先级依次尝试各个 Launcher
        for (launcher in launchers) {
            if (launcher.navigateByKeyword(context, dest)) {
                val message = when (launcher) {
                    is AmapAutoLauncher -> "正在为您导航到${dest}"
                    is AmapMobileLauncher -> "正在用高德地图导航到${dest}"
                    is BaiduLauncher -> "正在用百度地图搜索${dest}"
                    is GeoLauncher -> "正在搜索${dest}，请选择导航"
                    else -> "正在导航到${dest}"
                }
                return ExecutionResult(true, message)
            }
        }

        // 全部失败
        return ExecutionResult(false, "未找到可用的导航应用")
    }
}
