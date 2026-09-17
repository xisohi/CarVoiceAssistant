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
                            "&from=我的位置" +
                            "&referer=${context.packageName}"
                )
                setPackage(packageName)
                addCategory(android.content.Intent.CATEGORY_DEFAULT)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        )
    }

    /**
     * 重写 navigateByKeyword：前几种方式都失败时，直接启动腾讯地图主界面
     */
    override fun navigateByKeyword(context: Context, keyword: String): Boolean {
        if (super.navigateByKeyword(context, keyword)) return true

        // 方式3：直接启动腾讯地图主界面（兜底）
        return try {
            val launchIntent = context.packageManager.getLaunchIntentForPackage(packageName)
            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(launchIntent)
                android.util.Log.d("TencentMobileLauncher", "直接启动腾讯地图手机版主界面")
                true
            } else {
                android.util.Log.w("TencentMobileLauncher", "未找到腾讯地图手机版的启动 Intent")
                false
            }
        } catch (e: Exception) {
            android.util.Log.w("TencentMobileLauncher", "启动腾讯地图手机版失败: ${e.message}")
            false
        }
    }
}
