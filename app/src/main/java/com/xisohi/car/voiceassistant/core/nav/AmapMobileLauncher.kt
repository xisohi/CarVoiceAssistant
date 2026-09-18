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
        val encoded = java.net.URLEncoder.encode(keyword, "UTF-8")
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

    /**
     * 重写 navigateByKeyword：前两种方式都失败时，直接启动高德主界面
     */
    override fun navigateByKeyword(context: Context, keyword: String): Boolean {
        if (super.navigateByKeyword(context, keyword)) return true

        // 方式3：直接启动高德主界面（兜底）
        return try {
            val launchIntent = context.packageManager.getLaunchIntentForPackage(packageName)
            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(launchIntent)
                android.util.Log.d("AmapMobileLauncher", "直接启动高德地图手机版主界面")
                true
            } else {
                android.util.Log.w("AmapMobileLauncher", "未找到高德地图手机版的启动 Intent")
                false
            }
        } catch (e: Exception) {
            android.util.Log.w("AmapMobileLauncher", "启动高德地图手机版失败: ${e.message}")
            false
        }
    }
}
