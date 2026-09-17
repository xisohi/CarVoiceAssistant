package com.xisohi.car.voiceassistant.core.nav

import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * 百度地图汽车版导航拉起器
 *
 * 包名：com.baidu.naviauto
 *
 * URI Scheme：
 * - baidumap://map/direction（路线规划）
 * - baidumap://map/search（搜索）
 */
class BaiduLauncher : NavLauncher() {

    override val packageName = "com.baidu.naviauto"

    override fun buildKeywordIntents(
        context: Context,
        encodedKeyword: String
    ): List<Intent> {
        return listOf(
            // 方式1：路线规划
            Intent(Intent.ACTION_VIEW).apply {
                data = Uri.parse(
                    "baidumap://map/direction?" +
                            "destination=$encodedKeyword" +
                            "&mode=driving" +
                            "&src=voiceassistant"
                )
                setPackage(packageName)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            },
            // 方式2：搜索
            Intent(Intent.ACTION_VIEW).apply {
                data = Uri.parse(
                    "baidumap://map/search?" +
                            "query=$encodedKeyword" +
                            "&src=voiceassistant"
                )
                setPackage(packageName)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        )
    }
}
