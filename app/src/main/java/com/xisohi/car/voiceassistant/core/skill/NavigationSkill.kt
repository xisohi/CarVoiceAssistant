package com.xisohi.car.voiceassistant.core.skill

import android.content.Context
import com.xisohi.car.voiceassistant.core.ExecutionResult
import com.xisohi.car.voiceassistant.core.VoiceIntent
import com.xisohi.car.voiceassistant.core.nav.AmapAutoLauncher
import com.xisohi.car.voiceassistant.core.nav.AmapMobileLauncher
import com.xisohi.car.voiceassistant.core.nav.BaiduAutoLauncher
import com.xisohi.car.voiceassistant.core.nav.BaiduMobileLauncher
import com.xisohi.car.voiceassistant.core.nav.GeoLauncher
import com.xisohi.car.voiceassistant.core.nav.TencentMobileLauncher
import com.xisohi.car.voiceassistant.core.nav.TencentAutoLauncher

/**
 * 导航调度 Skill
 *
 * 职责：按优先级依次尝试各个导航应用
 *
 * 优先级（按易用程度排序）：
 * 1. 高德车机版（AmapAutoLauncher）- 搜索正常，不占麦，车机版体验最好
 * 2. 百度地图手机版（BaiduMobileLauncher）- 搜索正常，不占麦
 * 3. 高德手机版（AmapMobileLauncher）- 搜索正常，不占麦
 * 4. 腾讯地图手机版（TencentMobileLauncher）- 搜索正常，不占麦
 * 5. 百度地图汽车版（BaiduAutoLauncher）- 搜索正常，但强占麦克风
 * 6. 腾讯地图车机版（TencentAutoLauncher）- 只启动主界面，用户手动搜索
 * 7. 通用 geo: 协议（GeoLauncher）- 兜底
 *
 * 依赖：Context + 5个 Launcher
 */
class NavigationSkill(private val context: Context) {

    // 按优先级排序的 Launcher 列表（按易用程度排序）
    private val launchers = listOf(
        AmapAutoLauncher(),       // 1. 高德车机版（搜索正常，不占麦，体验最好）
        BaiduMobileLauncher(),    // 2. 百度地图手机版（搜索正常，不占麦）
        AmapMobileLauncher(),     // 3. 高德手机版（搜索正常，不占麦）
        TencentMobileLauncher(),  // 4. 腾讯地图手机版（搜索正常，不占麦）
        BaiduAutoLauncher(),      // 5. 百度地图汽车版（搜索正常，但强占麦克风）
        TencentAutoLauncher(),    // 6. 腾讯地图车机版（只启动主界面，用户手动搜索）
        GeoLauncher()             // 7. 通用 geo: 协议（兜底）
    )

    fun execute(intent: VoiceIntent): ExecutionResult {
        return when (intent.action) {
            "nav.home" -> navigateHome()
            "nav.company" -> navigateCompany()
            "nav.nearby" -> {
                val keyword = intent.params["keyword"] ?: ""
                navigateNearby(keyword)
            }
            else -> {
                val dest = intent.params["dest"] ?: ""
                navigate(dest)
            }
        }
    }

    /**
     * 导航回家（用导航应用里设置的"家"地址）
     */
    fun navigateHome(): ExecutionResult {
        for (launcher in launchers) {
            if (launcher.navigateHome(context)) {
                val message = when (launcher) {
                    is AmapAutoLauncher -> "正在为您搜索回家路线"
                    is AmapMobileLauncher -> "正在用高德地图导航回家"
                    is BaiduAutoLauncher -> "正在用百度地图汽车版导航回家"
                    is BaiduMobileLauncher -> "正在用百度地图导航回家"
                    is TencentMobileLauncher -> "正在用腾讯地图导航回家"
                    is TencentAutoLauncher -> "已打开腾讯地图车机版，请手动设置回家路线"
                    is GeoLauncher -> "正在搜索家的位置，请选择导航"
                    else -> "正在导航回家"
                }
                val needPause = launcher !is GeoLauncher && launcher.needsMicPause
                return ExecutionResult(true, message, needsMicPause = needPause)
            }
        }
        return ExecutionResult(false, "未找到可用的导航应用")
    }

    /**
     * 导航去公司（用导航应用里设置的"公司"地址）
     */
    fun navigateCompany(): ExecutionResult {
        for (launcher in launchers) {
            if (launcher.navigateCompany(context)) {
                val message = when (launcher) {
                    is AmapAutoLauncher -> "正在为您搜索去公司路线"
                    is AmapMobileLauncher -> "正在用高德地图导航去公司"
                    is BaiduAutoLauncher -> "正在用百度地图汽车版导航去公司"
                    is BaiduMobileLauncher -> "正在用百度地图导航去公司"
                    is TencentMobileLauncher -> "正在用腾讯地图导航去公司"
                    is TencentAutoLauncher -> "已打开腾讯地图车机版，请手动设置去公司路线"
                    is GeoLauncher -> "正在搜索公司的位置，请选择导航"
                    else -> "正在导航去公司"
                }
                val needPause = launcher !is GeoLauncher && launcher.needsMicPause
                return ExecutionResult(true, message, needsMicPause = needPause)
            }
        }
        return ExecutionResult(false, "未找到可用的导航应用")
    }

    /**
     * 附近搜索（搜索当前位置附近的地点）
     *
     * @param keyword 搜索关键词
     * @return ExecutionResult（handled=true 表示成功拉起）
     */
    fun navigateNearby(keyword: String): ExecutionResult {
        if (keyword.isBlank()) {
            return ExecutionResult(false, "请告诉我要搜索什么")
        }

        // 按优先级依次尝试各个 Launcher
        for (launcher in launchers) {
            if (launcher.navigateNearby(context, keyword)) {
                val message = when (launcher) {
                    is AmapAutoLauncher -> "正在为您搜索附近的${keyword}"
                    is AmapMobileLauncher -> "正在用高德地图搜索附近的${keyword}"
                    is BaiduAutoLauncher -> "正在用百度地图汽车版搜索附近的${keyword}"
                    is BaiduMobileLauncher -> "正在用百度地图搜索附近的${keyword}"
                    is TencentMobileLauncher -> "正在用腾讯地图搜索附近的${keyword}"
                    is TencentAutoLauncher -> "已打开腾讯地图车机版，请手动搜索附近的${keyword}"
                    is GeoLauncher -> "正在搜索附近的${keyword}，请选择"
                    else -> "正在搜索附近的${keyword}"
                }
                // geo 是系统弹窗，不需要让麦；其他导航根据 Launcher 的 needsMicPause 决定
                val needPause = launcher !is GeoLauncher && launcher.needsMicPause
                return ExecutionResult(true, message, needsMicPause = needPause)
            }
        }

        // 全部失败
        return ExecutionResult(false, "未找到可用的导航应用")
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
                    is AmapAutoLauncher -> "正在为您搜索${dest}，请选择目的地"
                    is AmapMobileLauncher -> "正在用高德地图导航到${dest}"
                    is BaiduAutoLauncher -> "正在用百度地图汽车版导航到${dest}"
                    is BaiduMobileLauncher -> "正在用百度地图导航到${dest}"
                    is TencentMobileLauncher -> "正在用腾讯地图导航到${dest}"
                    is TencentAutoLauncher -> "已打开腾讯地图车机版，请手动输入目的地"
                    is GeoLauncher -> "正在搜索${dest}，请选择导航"
                    else -> "正在导航到${dest}"
                }
                // geo 是系统弹窗，不需要让麦；其他导航根据 Launcher 的 needsMicPause 决定
                val needPause = launcher !is GeoLauncher && launcher.needsMicPause
                return ExecutionResult(true, message, needsMicPause = needPause)
            }
        }

        // 全部失败
        return ExecutionResult(false, "未找到可用的导航应用")
    }
}
