package com.xisohi.car.voiceassistant.core.nav

import android.content.Context
import android.content.Intent

/**
 * 导航拉起器抽象基类
 *
 * 职责：
 * - 定义导航拉起的通用接口
 * - 提供通用工具方法（tryStartActivity、isAppInstalled）
 * - 子类只需要实现 URI 拼接逻辑
 *
 * 子类：
 * - AmapAutoLauncher（高德车机版）
 * - AmapMobileLauncher（高德手机版）
 * - BaiduAutoLauncher（百度汽车版）
 * - BaiduMobileLauncher（百度手机版）
 * - TencentMobileLauncher（腾讯手机版）
 * - TencentAutoLauncher（腾讯车机版）
 * - GeoLauncher（geo 兜底）
 */
abstract class NavLauncher {

    /** 目标应用的包名 */
    abstract val packageName: String

    /**
     * 拉起导航后是否需要暂停唤醒监听给导航让麦。
     * true=导航应用有内置语音助手，要听用户说"选1"等，需要我们释放麦克风
     * false=导航应用没有语音助手，只显示列表用户手动点，不需要让麦
     *
     * 默认 false。测试发现某个导航应用支持语音选择时，在对应 Launcher 里重写为 true。
     */
    open val needsMicPause: Boolean = false

    /**
     * 构建关键字导航的 Intent 列表（按优先级排序）
     * 子类实现，只写 URI 拼接逻辑
     */
    protected abstract fun buildKeywordIntents(
        context: Context,
        encodedKeyword: String
    ): List<Intent>

    /**
     * 检查该导航应用是否可用
     */
    open fun isAvailable(context: Context): Boolean = isAppInstalled(context, packageName)

    /**
     * 用关键字拉起导航
     * @return true=成功拉起，false=失败
     *
     * open：子类可以重写（比如 AmapAutoLauncher 需要在最后加"直接启动主界面"的兜底）
     */
    open fun navigateByKeyword(context: Context, keyword: String): Boolean {
        if (!isAvailable(context)) return false
        val encoded = java.net.URLEncoder.encode(keyword, "UTF-8")
        val intents = buildKeywordIntents(context, encoded)
        for (intent in intents) {
            if (tryStartActivity(context, intent)) {
                return true
            }
        }
        return false
    }

    /**
     * 导航回家（导航应用里设置的"家"地址）
     * 默认用"家"作为关键字搜索（兜底），支持专门接口的子类重写。
     */
    open fun navigateHome(context: Context): Boolean = navigateByKeyword(context, "家")

    /**
     * 导航去公司（导航应用里设置的"公司"地址）
     * 默认用"公司"作为关键字搜索（兜底），支持专门接口的子类重写。
     */
    open fun navigateCompany(context: Context): Boolean = navigateByKeyword(context, "公司")

    /**
     * 附近搜索（搜索当前位置附近的地点）
     * 默认用普通关键字搜索（兜底），支持附近搜索接口的子类重写。
     */
    open fun navigateNearby(context: Context, keyword: String): Boolean = navigateByKeyword(context, keyword)

    /**
     * 尝试启动 Activity，成功返回 true
     */
    protected fun tryStartActivity(context: Context, intent: Intent): Boolean {
        return try {
            val resolveInfo = context.packageManager.resolveActivity(intent, 0)
            if (resolveInfo == null) {
                android.util.Log.w("NavLauncher", "无应用处理: ${intent.data} (pkg=${intent.`package`})")
                return false
            }
            android.util.Log.d("NavLauncher", "拉起: ${intent.data} -> ${resolveInfo.activityInfo.packageName}/${resolveInfo.activityInfo.name}")
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            android.util.Log.w("NavLauncher", "启动失败: ${intent.data}, 错误: ${e.message}")
            false
        }
    }

    /**
     * 检查应用是否已安装
     */
    protected fun isAppInstalled(context: Context, packageName: String): Boolean {
        return try {
            context.packageManager.getPackageInfo(packageName, 0)
            true
        } catch (e: Exception) {
            false
        }
    }
}
