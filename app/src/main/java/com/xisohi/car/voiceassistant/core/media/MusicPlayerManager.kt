package com.xisohi.car.voiceassistant.core.media

import android.content.Context
import android.content.Intent

/**
 * 音乐播放器管理器
 *
 * 职责：
 * - 维护播放器优先级列表
 * - 管理当前活跃播放器
 * - 启动播放器
 *
 * 依赖：Context
 */
class MusicPlayerManager(private val context: Context) {

    // 音乐播放器优先级列表（从高到低）
    private val musicPlayerPackages = listOf(
        "fun.upup.musicfree",          // MusicFree（开源，支持自动搜索播放）
        "com.netease.cloudmusic.iot",  // 网易云音乐车机版（IoT）
        "com.tencent.qqmusiccar",      // QQ音乐车机版
        "com.kugou.android.auto",      // 酷狗音乐车机版
        "cn.kuwo.kwmusiccar",          // 酷我音乐车机版
        "cn.kuwo.autolite",            // 酷我音乐车简版
        "com.netease.cloudmusic",      // 网易云音乐手机版
        "com.luna.music",              // 汽水音乐
        "com.tencent.qqmusic",         // QQ音乐
        "com.kugou.android",           // 酷狗音乐
        "cn.kuwo.player"               // 酷我音乐
    )

    // 当前活跃的音乐播放器包名（null=未设置，按优先级选择）
    @Volatile
    private var currentMusicPlayer: String? = null

    /**
     * 获取当前应该使用的音乐播放器。
     * 优先使用已记录的活跃播放器；如果未设置，按优先级选择第一个已安装的。
     */
    fun getActivePlayer(): String? {
        // 1. 优先使用已记录的活跃播放器
        currentMusicPlayer?.let { pkg ->
            if (isAppInstalled(pkg)) return pkg
        }
        // 2. 按优先级选择第一个已安装的
        for (pkg in musicPlayerPackages) {
            if (isAppInstalled(pkg)) {
                currentMusicPlayer = pkg
                return pkg
            }
        }
        return null
    }

    /**
     * 设置当前活跃的音乐播放器（用户打开或切换播放器时调用）。
     */
    fun setActivePlayer(packageName: String) {
        if (isAppInstalled(packageName)) {
            currentMusicPlayer = packageName
            android.util.Log.d("MusicPlayerManager", "当前活跃音乐播放器: $packageName")
        }
    }

    /**
     * 获取所有播放器包名列表（用于 MediaKeyDispatcher 兜底广播）
     */
    fun getAllPlayerPackages(): List<String> = musicPlayerPackages

    /**
     * 判断包名是否是已知的音乐播放器
     */
    fun isMusicPlayer(packageName: String): Boolean =
        packageName in musicPlayerPackages

    /**
     * 启动指定包名的播放器（带到前台，确保 Service 运行）。
     */
    fun launchPlayer(packageName: String) {
        try {
            val intent = context.packageManager.getLaunchIntentForPackage(packageName)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                context.startActivity(intent)
                android.util.Log.d("MusicPlayerManager", "已启动播放器: $packageName")
            }
        } catch (e: Exception) {
            android.util.Log.w("MusicPlayerManager", "启动播放器失败: $packageName, ${e.message}")
        }
    }

    /**
     * 启动音乐播放器（按优先级选择）。
     *
     * 优先级：
     * 1. 当前活跃播放器
     * 2. 按优先级选择第一个已安装的
     * 3. 系统默认音乐播放器（兜底）
     */
    fun launchMusicPlayer() {
        val playerPkg = getActivePlayer()
        if (playerPkg != null) {
            currentMusicPlayer = playerPkg
            launchPlayer(playerPkg)
            return
        }
        // 兜底：系统默认音乐播放器
        try {
            val intent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_APP_MUSIC)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            android.util.Log.d("MusicPlayerManager", "已启动系统默认音乐播放器")
        } catch (_: Exception) {
        }
    }

    /** 检查应用是否已安装 */
    private fun isAppInstalled(packageName: String): Boolean {
        return try {
            context.packageManager.getPackageInfo(packageName, 0)
            true
        } catch (e: Exception) {
            false
        }
    }
}
