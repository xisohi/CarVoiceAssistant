package com.xisohi.car.voiceassistant.core.nav

import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * 高德地图手机版导航拉起器
 *
 * 包名：com.autonavi.minimap
 *
 * URI Scheme：
 * - androidamap://poi（POI 搜索，显示结果列表让用户选择，优先）
 * - androidamap://route（路线规划/导航，兜底）
 * - 直接启动主界面（兜底）
 */
class AmapMobileLauncher : NavLauncher() {

    override val needsMicPause = false  // 经实测不支持语音选择，不需要让麦

    override val packageName = "com.autonavi.minimap"

    override fun buildKeywordIntents(
        context: Context,
        encodedKeyword: String
    ): List<Intent> {
        return listOf(
            // 方式1：POI 搜索（显示结果列表让用户选择，避免直接导航到错误的第一个结果）
            Intent(Intent.ACTION_VIEW).apply {
                data = Uri.parse(
                    "androidamap://poi?" +
                            "sourceApplication=voiceassistant" +
                            "&keywords=$encodedKeyword"
                )
                setPackage(packageName)
                addCategory(android.content.Intent.CATEGORY_DEFAULT)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            },
            // 方式2：路线规划/导航（兜底，poi 失败时用）
            Intent(Intent.ACTION_VIEW).apply {
                data = Uri.parse(
                    "androidamap://route?" +
                            "sourceApplication=voiceassistant" +
                            "&dname=$encodedKeyword" +
                            "&dev=0&t=0"
                )
                setPackage(packageName)
                addCategory(android.content.Intent.CATEGORY_DEFAULT)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        )
    }

    /**
     * 附近搜索（高德手机版专门接口）
     * URI: androidamap://arroundpoi?keywords=XXX
     */
    override fun navigateNearby(context: Context, keyword: String): Boolean {
        if (!isAvailable(context)) return false
        val encoded = java.net.URLEncoder.encode(keyword, "UTF-8").replace("+", "%20")  // ★ 空格不能编成 '+'（导航 App 不还原），同基类修复
        val intent = Intent(Intent.ACTION_VIEW).apply {
            data = Uri.parse(
                "androidamap://arroundpoi?" +
                        "sourceApplication=voiceassistant" +
                        "&keywords=$encoded" +
                        "&dev=0"
            )
            setPackage(packageName)
            addCategory(android.content.Intent.CATEGORY_DEFAULT)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (tryStartActivity(context, intent)) {
            android.util.Log.d("AmapMobileLauncher", "附近搜索成功: $keyword")
            return true
        }
        android.util.Log.w("AmapMobileLauncher", "附近搜索失败，fallback 到普通搜索")
        return super.navigateNearby(context, keyword)
    }

    // ★ 修复6：这里原本重写 navigateByKeyword，在 URI 全部失败时"直接启动主界面 + return true"，
    // 使 NavigationSkill 认为已处理而停止遍历，优先级更低（搜索其实可用）的导航永远轮不到。
    // 现沿用基类：URI 都失败 → 返回 false → 交给下一个 Launcher。
    // "启动主界面"这最后一层兜底只保留在 TencentAutoLauncher（优先级 6，geo 之前那个）。
}
