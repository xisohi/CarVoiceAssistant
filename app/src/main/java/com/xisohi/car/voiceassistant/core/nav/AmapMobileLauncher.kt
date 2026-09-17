package com.xisohi.car.voiceassistant.core.nav

import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * 高德手机版导航拉起器
 *
 * 包名：com.autonavi.minimap
 *
 * URI Scheme：
 * - androidamap://route（路线规划）
 */
class AmapMobileLauncher : NavLauncher() {

    override val packageName = "com.autonavi.minimap"

    override fun buildKeywordIntents(
        context: Context,
        encodedKeyword: String
    ): List<Intent> {
        return listOf(
            Intent(Intent.ACTION_VIEW).apply {
                data = Uri.parse(
                    "androidamap://route?" +
                            "sourceApplication=voiceassistant" +
                            "&dname=$encodedKeyword" +
                            "&dev=0&t=0"
                )
                setPackage(packageName)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        )
    }
}
