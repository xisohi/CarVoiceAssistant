package com.xisohi.car.voiceassistant.core.nav

import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * 腾讯地图手机版导航拉起器
 *
 * 包名：com.tencent.map（手机版，官方确认支持 qqmap:// URI Scheme）
 *
 * 官方文档：https://lbs.qq.com/webApi/uriV1/uriGuide/uriMobileGuide
 *
 * URI Scheme（按优先级尝试）：
 * 1. qqmap://map/search（搜索，显示结果列表让用户选择）
 * 2. qqmap://map/routeplan（路线规划/导航，兜底）
 * 3. 直接启动主界面（兜底）
 */
class TencentMobileLauncher : NavLauncher() {

    override val needsMicPause = false  // 经实测不支持语音选择，不需要让麦

    override val packageName = "com.tencent.map"

    override fun buildKeywordIntents(
        context: Context,
        encodedKeyword: String
    ): List<Intent> {
        // ★ 修复8：from 参数与其他参数保持一致，先 URLEncoder 再拼接。
        // 原实现把中文"我的位置"直接写进 URI（from=我的位置），中文没有百分号编码，
        // URI 在某些 ROM/Intent 传递链上会被截断或解析失败（腾讯地图拿不到 from）。
        // 注意 URLEncoder 会把空格编成 '+'，这里同样替换成 %20（见 NavLauncher 修复7）。
        val encodedFrom = java.net.URLEncoder.encode("我的位置", "UTF-8").replace("+", "%20")
        return listOf(
            // 方式1：qqmap search（搜索，显示结果列表让用户选择，避免直接导航到错误的第一个结果）
            // 官方文档：https://lbs.qq.com/webApi/uriV1/uriGuide/uriMobilePoisearch
            Intent(Intent.ACTION_VIEW).apply {
                data = Uri.parse(
                    "qqmap://map/search?" +
                            "keyword=$encodedKeyword" +
                            "&center=CurrentLocation" +
                            "&referer=${context.packageName}"
                )
                setPackage(packageName)
                addCategory(android.content.Intent.CATEGORY_DEFAULT)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            },
            // 方式2：qqmap routeplan（路线规划/导航，兜底，search 失败时用）
            Intent(Intent.ACTION_VIEW).apply {
                data = Uri.parse(
                    "qqmap://map/routeplan?" +
                            "type=drive" +
                            "&to=$encodedKeyword" +
                            "&from=$encodedFrom" +
                            "&referer=${context.packageName}"
                )
                setPackage(packageName)
                addCategory(android.content.Intent.CATEGORY_DEFAULT)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        )
    }

    // ★ 修复6：这里原本重写 navigateByKeyword，在 URI 全部失败时"直接启动主界面 + return true"，
    // 使 NavigationSkill 认为已处理而停止遍历，优先级更低（搜索其实可用）的导航永远轮不到。
    // 现沿用基类：URI 都失败 → 返回 false → 交给下一个 Launcher。
    // "启动主界面"这最后一层兜底只保留在 TencentAutoLauncher（优先级 6，geo 之前那个）。
}
