package com.xisohi.car.voiceassistant.core.nav

import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * 高德车机版导航拉起器
 *
 * 包名：com.autonavi.amapauto
 *
 * URI Scheme：
 * - androidauto://keywordNavi（搜索+导航一体化，优先）
 * - androidauto://poi（打开搜索页）
 *
 * ★ 修复6：不再保留"直接启动高德主界面"的兜底。原来两个 URI 都失败时它会启动高德主界面
 * 并 return true，NavigationSkill 拿到 true 就认为"已处理"而停止遍历，
 * 于是优先级更低、搜索其实可用的百度/高德手机版永远不会被尝试，用户只看到一个没有搜索结果的空地图。
 * 现在两个 URI 都失败时返回 false（基类契约：false = 未处理，交给下一个 Launcher 继续尝试）。
 *
 * 注意：使用 keywordNavi 后，高德会自己搜索并弹出结果列表，用户手动选择。
 * 经实测高德车机版没有语音助手，不需要让麦。
 */
class AmapAutoLauncher : NavLauncher() {

    override val needsMicPause = false  // 经实测不支持语音选择，不需要让麦

    override val packageName = "com.autonavi.amapauto"

    override fun buildKeywordIntents(
        context: Context,
        encodedKeyword: String
    ): List<Intent> {
        return listOf(
            // 方式1：keywordNavi（搜索+导航一体化，优先）
            Intent(Intent.ACTION_VIEW).apply {
                data = Uri.parse(
                    "androidauto://keywordNavi?" +
                            "sourceApplication=${context.packageName}" +
                            "&keywords=$encodedKeyword" +
                            "&style=2"
                )
                setPackage(packageName)
                addCategory("android.intent.category.DEFAULT")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            },
            // 方式2：poi 搜索（打开搜索页，显示结果列表）
            Intent(Intent.ACTION_VIEW).apply {
                data = Uri.parse(
                    "androidauto://poi?" +
                            "sourceApplication=${context.packageName}" +
                            "&keywords=$encodedKeyword"
                )
                setPackage(packageName)
                addCategory("android.intent.category.DEFAULT")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        )
    }

    /**
     * 附近搜索（高德车机版专门接口）
     * URI: androidauto://arroundpoi?keywords=XXX
     */
    override fun navigateNearby(context: Context, keyword: String): Boolean {
        if (!isAvailable(context)) return false
        val encoded = java.net.URLEncoder.encode(keyword, "UTF-8").replace("+", "%20")  // ★ 空格不能编成 '+'（导航 App 不还原），同基类修复
        val intent = Intent(Intent.ACTION_VIEW).apply {
            data = Uri.parse(
                "androidauto://arroundpoi?" +
                        "sourceApplication=${context.packageName}" +
                        "&keywords=$encoded" +
                        "&style=2"
            )
            setPackage(packageName)
            addCategory(android.content.Intent.CATEGORY_DEFAULT)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (tryStartActivity(context, intent)) {
            android.util.Log.d("AmapAutoLauncher", "附近搜索成功: $keyword")
            return true
        }
        android.util.Log.w("AmapAutoLauncher", "附近搜索失败，fallback 到普通搜索")
        return super.navigateNearby(context, keyword)
    }
}
