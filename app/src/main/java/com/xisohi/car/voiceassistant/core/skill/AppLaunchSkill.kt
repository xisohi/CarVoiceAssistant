package com.xisohi.car.voiceassistant.core.skill

import android.content.Context
import android.content.Intent
import com.xisohi.car.voiceassistant.core.ExecutionResult
import com.xisohi.car.voiceassistant.core.VoiceIntent

/**
 * 打开应用 Skill
 *
 * 职责：打开指定应用/设置项
 *
 * 策略：
 * 1. 精确匹配已知应用包名
 * 2. 匹配设置项 Action
 * 3. 模糊匹配已安装应用
 *
 * 依赖：Context + MediaSkill（打开音乐播放器时自动播放）
 */
class AppLaunchSkill(
    private val context: Context,
    private val mediaSkill: MediaSkill
) {

    /** 应用名到包名/Action 的映射表 */
    private val appMap = mapOf(
        // 地图（汽车版包名）
        "百度地图" to "com.baidu.naviauto",
        "百度地图汽车版" to "com.baidu.naviauto",
        "高德地图" to "com.autonavi.amapauto",
        "高德" to "com.autonavi.amapauto",
        // 音乐
        "音乐" to "fun.upup.musicfree",
        "MusicFree" to "fun.upup.musicfree",
        "网易云音乐" to "com.netease.cloudmusic",
        "网易云" to "com.netease.cloudmusic",
        "QQ音乐" to "com.tencent.qqmusic",
        "酷狗音乐" to "com.kugou.android",
        "酷狗" to "com.kugou.android",
        // 设置
        "设置" to "com.android.settings",
        "系统设置" to "com.android.settings",
        // 浏览器
        "浏览器" to "com.android.browser",
    )

    /** 设置项到 Settings Action 的映射 */
    private val settingsActionMap = mapOf(
        "蓝牙" to android.provider.Settings.ACTION_BLUETOOTH_SETTINGS,
        "WiFi" to android.provider.Settings.ACTION_WIFI_SETTINGS,
        "wifi" to android.provider.Settings.ACTION_WIFI_SETTINGS,
        "网络" to android.provider.Settings.ACTION_WIRELESS_SETTINGS,
        "显示" to android.provider.Settings.ACTION_DISPLAY_SETTINGS,
        "声音" to android.provider.Settings.ACTION_SOUND_SETTINGS,
        "应用" to android.provider.Settings.ACTION_APPLICATION_SETTINGS,
        "存储" to android.provider.Settings.ACTION_INTERNAL_STORAGE_SETTINGS,
    )

    fun execute(intent: VoiceIntent): ExecutionResult =
        openApp(intent.params["app"] ?: "")

    /**
     * 打开指定应用。
     * 优先匹配已知应用包名；其次匹配设置项 Action；最后尝试模糊匹配已安装应用。
     */
    private fun openApp(appName: String): ExecutionResult {
        val name = appName.trim()
        if (name.isEmpty()) return ExecutionResult(false, "没听清要打开什么")

        // 1. 精确匹配已知应用
        val packageName = appMap[name]
        if (packageName != null) {
            return launchByPackage(packageName, name)
        }

        // 2. 匹配设置项（如"打开蓝牙"→ 蓝牙设置页）
        val settingsAction = settingsActionMap[name]
        if (settingsAction != null) {
            return try {
                val intent = Intent(settingsAction)
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                ExecutionResult(true, "已打开${name}设置")
            } catch (e: Exception) {
                ExecutionResult(false, "打开${name}设置失败")
            }
        }

        // 3. 模糊匹配已安装应用（应用名包含关键词）
        val pm = context.packageManager
        val apps = pm.getInstalledApplications(0)
        val matched = apps.firstOrNull { appInfo ->
            val label = pm.getApplicationLabel(appInfo).toString()
            label.contains(name) || name.contains(label)
        }
        if (matched != null) {
            return launchByPackage(matched.packageName, pm.getApplicationLabel(matched).toString())
        }

        return ExecutionResult(false, "未找到应用「$name」")
    }

    /** 通过包名启动应用 */
    private fun launchByPackage(packageName: String, displayName: String): ExecutionResult {
        return try {
            val intent = context.packageManager.getLaunchIntentForPackage(packageName)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                // 如果打开的是音乐播放器，只设置为当前活跃播放器，不自动播放
                // 用户说"打开音乐"→ 只打开；用户说"播放音乐"（media.play）→ 才自动播放
                if (mediaSkill.isMusicPlayer(packageName)) {
                    mediaSkill.setActivePlayer(packageName)
                }
                ExecutionResult(true, "已打开$displayName")
            } else {
                ExecutionResult(false, "未安装$displayName")
            }
        } catch (e: Exception) {
            ExecutionResult(false, "打开$displayName 失败：${e.message}")
        }
    }
}
