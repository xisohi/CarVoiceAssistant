package com.xisohi.car.voiceassistant.core.nav

import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * 通用 geo: 协议导航拉起器（兜底）
 *
 * 不指定包名，系统会选择默认地图应用。
 *
 * URI Scheme：
 * - geo:0,0?q=关键字
 */
class GeoLauncher : NavLauncher() {

    override val packageName = ""  // 不指定包名，系统选择默认应用

    /** geo 协议不需要检查特定应用是否安装 */
    override fun isAvailable(context: Context): Boolean = true

    override fun buildKeywordIntents(
        context: Context,
        encodedKeyword: String
    ): List<Intent> {
        return listOf(
            Intent(Intent.ACTION_VIEW).apply {
                data = Uri.parse("geo:0,0?q=$encodedKeyword")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        )
    }
}
