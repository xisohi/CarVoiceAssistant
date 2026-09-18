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
 * - baidumap://map/place/search（地点搜索，显示结果列表让用户选择）
 * - baidumap://map/direction（路线规划/导航，兜底）
 */
class BaiduAutoLauncher : NavLauncher() {

    override val needsMicPause = false  // 经实测不支持语音选择，不需要让麦

    override val packageName = "com.baidu.naviauto"

    override fun buildKeywordIntents(
        context: Context,
        encodedKeyword: String
    ): List<Intent> {
        return listOf(
            // 方式1：地点搜索（显示搜索结果列表，让用户选择）
            // 注意：百度车机版用 /map/place/search，不是 /map/search（后者不生效）
            Intent(Intent.ACTION_VIEW).apply {
                data = Uri.parse(
                    "baidumap://map/place/search?" +
                            "query=$encodedKeyword" +
                            "&src=voiceassistant"
                )
                setPackage(packageName)
                addCategory(android.content.Intent.CATEGORY_DEFAULT)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            },
            // 方式2：路线规划/导航（兜底，place/search 失败时用）
            Intent(Intent.ACTION_VIEW).apply {
                data = Uri.parse(
                    "baidumap://map/direction?" +
                            "destination=$encodedKeyword" +
                            "&mode=driving" +
                            "&src=voiceassistant"
                )
                setPackage(packageName)
                addCategory(android.content.Intent.CATEGORY_DEFAULT)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        )
    }

    /**
     * 附近搜索（百度地图专门接口）
     * URI: baidumap://map/place/nearby?query=XXX
     */
    override fun navigateNearby(context: Context, keyword: String): Boolean {
        if (!isAvailable(context)) return false
        val encoded = java.net.URLEncoder.encode(keyword, "UTF-8")
        val intent = Intent(Intent.ACTION_VIEW).apply {
            data = Uri.parse(
                "baidumap://map/place/nearby?" +
                        "query=$encoded" +
                        "&src=voiceassistant"
            )
            setPackage(packageName)
            addCategory(android.content.Intent.CATEGORY_DEFAULT)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (tryStartActivity(context, intent)) {
            android.util.Log.d("BaiduAutoLauncher", "附近搜索成功: $keyword")
            return true
        }
        android.util.Log.w("BaiduAutoLauncher", "附近搜索失败，fallback 到普通搜索")
        return super.navigateNearby(context, keyword)
    }

    /**
     * 重写 navigateByKeyword：前两种方式都失败时，直接启动百度地图主界面
     */
    override fun navigateByKeyword(context: Context, keyword: String): Boolean {
        if (super.navigateByKeyword(context, keyword)) return true

        // 方式3：直接启动百度地图主界面（兜底）
        return try {
            val launchIntent = context.packageManager.getLaunchIntentForPackage(packageName)
            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(launchIntent)
                android.util.Log.d("BaiduAutoLauncher", "直接启动百度地图汽车版主界面")
                true
            } else {
                android.util.Log.w("BaiduAutoLauncher", "未找到百度地图汽车版的启动 Intent")
                false
            }
        } catch (e: Exception) {
            android.util.Log.w("BaiduAutoLauncher", "启动百度地图汽车版失败: ${e.message}")
            false
        }
    }

    /**
     * 导航回家（百度地图专门接口）
     * URI: baidumap://map/navi/common?addr=home
     */
    override fun navigateHome(context: Context): Boolean {
        if (!isAvailable(context)) return false
        val intent = Intent(Intent.ACTION_VIEW).apply {
            data = Uri.parse("baidumap://map/navi/common?addr=home&coord_type=bd09ll&src=voiceassistant")
            setPackage(packageName)
            addCategory(android.content.Intent.CATEGORY_DEFAULT)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (tryStartActivity(context, intent)) {
            android.util.Log.d("BaiduAutoLauncher", "用专门接口导航回家成功")
            return true
        }
        android.util.Log.w("BaiduAutoLauncher", "专门接口导航回家失败，fallback 到关键字搜索")
        return super.navigateHome(context)
    }

    /**
     * 导航去公司（百度地图专门接口）
     * URI: baidumap://map/navi/common?addr=company
     */
    override fun navigateCompany(context: Context): Boolean {
        if (!isAvailable(context)) return false
        val intent = Intent(Intent.ACTION_VIEW).apply {
            data = Uri.parse("baidumap://map/navi/common?addr=company&coord_type=bd09ll&src=voiceassistant")
            setPackage(packageName)
            addCategory(android.content.Intent.CATEGORY_DEFAULT)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (tryStartActivity(context, intent)) {
            android.util.Log.d("BaiduAutoLauncher", "用专门接口导航去公司成功")
            return true
        }
        android.util.Log.w("BaiduAutoLauncher", "专门接口导航去公司失败，fallback 到关键字搜索")
        return super.navigateCompany(context)
    }
}

