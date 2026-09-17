package com.xisohi.car.voiceassistant.core.nav

import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * 腾讯地图车机版导航拉起器
 *
 * 包名：com.tencent.wecarnavi（腾讯梧桐车联地图，车机版）
 *
 * URI Scheme（按优先级尝试，车机版可能只支持其中部分）：
 * 1. qqmap://map/routeplan（通用腾讯地图路线规划）
 * 2. qqmap://map/search（通用腾讯地图搜索）
 * 3. tencentmap://map/routeplan（另一种可能的 scheme）
 * 4. 直接启动主界面（兜底）
 *
 * 注意：如果以上 URI Scheme 都无法拉起，可以通过以下命令查看车机版支持的 scheme：
 * adb shell dumpsys package com.tencent.wecarnavi | grep -A 5 "scheme"
 * 然后把支持的 scheme 添加到 buildKeywordIntents() 列表中。
 */
class TencentLauncher : NavLauncher() {

    override val packageName = "com.tencent.wecarnavi"

    override fun buildKeywordIntents(
        context: Context,
        encodedKeyword: String
    ): List<Intent> {
        return listOf(
            // 方式1：qqmap routeplan（通用腾讯地图路线规划，优先）
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
            },
            // 方式2：qqmap search（通用腾讯地图搜索）
            Intent(Intent.ACTION_VIEW).apply {
                data = Uri.parse(
                    "qqmap://map/search?" +
                            "keyword=$encodedKeyword" +
                            "&referer=${context.packageName}"
                )
                setPackage(packageName)
                addCategory(android.content.Intent.CATEGORY_DEFAULT)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            },
            // 方式3：tencentmap routeplan（另一种可能的 scheme）
            Intent(Intent.ACTION_VIEW).apply {
                data = Uri.parse(
                    "tencentmap://map/routeplan?" +
                            "type=drive" +
                            "&to=$encodedKeyword" +
                            "&from=我的位置"
                )
                setPackage(packageName)
                addCategory(android.content.Intent.CATEGORY_DEFAULT)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            },
            // 方式4：tencentmap search（另一种可能的 scheme）
            Intent(Intent.ACTION_VIEW).apply {
                data = Uri.parse(
                    "tencentmap://map/search?" +
                            "keyword=$encodedKeyword"
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

        // 方式5：直接启动腾讯地图主界面（兜底）
        return try {
            val launchIntent = context.packageManager.getLaunchIntentForPackage(packageName)
            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(launchIntent)
                android.util.Log.d("TencentLauncher", "直接启动腾讯地图车机版主界面")
                true
            } else {
                android.util.Log.w("TencentLauncher", "未找到腾讯地图车机版的启动 Intent")
                false
            }
        } catch (e: Exception) {
            android.util.Log.w("TencentLauncher", "启动腾讯地图车机版失败: ${e.message}")
            false
        }
    }
}
