package com.xisohi.car.voiceassistant.core.nav

import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * 腾讯地图车机版导航拉起器
 *
 * 包名：com.tencent.wecarnavi（腾讯梧桐车联地图，车机版）
 *
 * 注意：车机版不支持 qqmap:// URI Scheme（即使注册了 scheme 也无法处理搜索/导航请求）。
 * 经实测：直接发送 qqmap:// URI 会报 "No Activity found to handle Intent"。
 *
 * 因此本拉起器只启动腾讯地图车机版主界面，用户手动输入目的地搜索。
 */
class TencentAutoLauncher : NavLauncher() {

    override val needsMicPause = false  // 测试阶段默认需要让麦true，确认不支持语音选择后改回 false

    override val packageName = "com.tencent.wecarnavi"

    /**
     * 只启动主界面，不尝试发送 URI（车机版不支持 qqmap:// URI）
     */
    override fun navigateByKeyword(context: Context, keyword: String): Boolean {
        return try {
            val launchIntent = context.packageManager.getLaunchIntentForPackage(packageName)
            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(launchIntent)
                android.util.Log.d("TencentAutoLauncher", "启动腾讯地图车机版主界面（不支持直接搜索，请手动输入目的地）")
                true
            } else {
                android.util.Log.w("TencentAutoLauncher", "未找到腾讯地图车机版的启动 Intent")
                false
            }
        } catch (e: Exception) {
            android.util.Log.w("TencentAutoLauncher", "启动腾讯地图车机版失败: ${e.message}")
            false
        }
    }

    /**
     * buildKeywordIntents 保留空实现（车机版不走基类的 URI 尝试逻辑）
     */
    override fun buildKeywordIntents(
        context: Context,
        encodedKeyword: String
    ): List<Intent> {
        return emptyList()
    }
}
