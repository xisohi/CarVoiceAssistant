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
 * - 直接启动主界面（兜底）
 *
 * 注意：高德车机版原生支持多结果语音选择，
 * 使用 keywordNavi 后，高德会自己搜索、弹出列表、提示用户说"选几"。
 */
class AmapAutoLauncher : NavLauncher() {

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
                true
            } else {
                false
            }
        } catch (e: Exception) {
            false
        }
    }
}
