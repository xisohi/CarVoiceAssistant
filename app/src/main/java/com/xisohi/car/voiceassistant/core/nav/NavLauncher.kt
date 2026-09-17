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
 * - BaiduLauncher（百度汽车版）
 * - GeoLauncher（geo 兜底）
 */
abstract class NavLauncher {

    /** 目标应用的包名 */
    abstract val packageName: String

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
