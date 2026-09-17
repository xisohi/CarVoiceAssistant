package com.xisohi.car.voiceassistant.core.skill

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.xisohi.car.voiceassistant.core.ExecutionResult
import com.xisohi.car.voiceassistant.core.VoiceIntent
import com.xisohi.car.voiceassistant.core.media.MediaKeyDispatcher
import com.xisohi.car.voiceassistant.core.media.MusicPlayerManager

/**
 * 媒体控制 Skill
 *
 * 职责：
 * - 播放/暂停/下一首/上一首
 * - 选择歌曲（占位，需要用户手动操作）
 * - 打开音乐播放器时自动播放
 *
 * 依赖：Context + MediaKeyDispatcher + MusicPlayerManager
 */
class MediaSkill(private val context: Context) {

    private val mediaKeyDispatcher = MediaKeyDispatcher(context)
    private val musicPlayerManager = MusicPlayerManager(context)
    private val mainHandler = Handler(Looper.getMainLooper())

    fun execute(intent: VoiceIntent): ExecutionResult = when (intent.action) {
        "media.play" -> mediaKey(android.view.KeyEvent.KEYCODE_MEDIA_PLAY)
        "media.pause" -> mediaKey(android.view.KeyEvent.KEYCODE_MEDIA_PAUSE)
        "media.next" -> mediaKey(android.view.KeyEvent.KEYCODE_MEDIA_NEXT)
        "media.prev" -> mediaKey(android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS)
        else -> ExecutionResult(false, "不支持的媒体指令")
    }

    /**
     * 音乐控制。
     * 所有操作（播放/暂停/下一首/上一首）都立即发送媒体按键，不启动播放器。
     * 启动播放器的指令是"打开音乐"、"打开播放器"等（走 app.open 意图）。
     */
    private fun mediaKey(keyCode: Int): ExecutionResult {
        val label = when (keyCode) {
            android.view.KeyEvent.KEYCODE_MEDIA_PLAY -> "继续播放"
            android.view.KeyEvent.KEYCODE_MEDIA_PAUSE -> "已暂停"
            android.view.KeyEvent.KEYCODE_MEDIA_NEXT -> "下一首"
            android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS -> "上一首"
            else -> "好的"
        }
        return try {
            val playerPkg = musicPlayerManager.getActivePlayer()
            if (playerPkg == null) {
                return ExecutionResult(false, "未安装任何音乐播放器，请先说打开音乐")
            }
            // 记录当前活跃播放器
            musicPlayerManager.setActivePlayer(playerPkg)
            // 发送媒体按键
            mediaKeyDispatcher.dispatch(
                keyCode,
                playerPkg,
                musicPlayerManager.getAllPlayerPackages()
            )
            ExecutionResult(true, label)
        } catch (e: Exception) {
            ExecutionResult(false, "音乐控制失败：${e.message ?: "未知错误"}")
        }
    }

    /**
     * 判断包名是否是已知的音乐播放器
     */
    fun isMusicPlayer(packageName: String): Boolean =
        musicPlayerManager.isMusicPlayer(packageName)

    /**
     * 打开音乐播放器时的回调（由 AppLaunchSkill 或 SkillExecutor 调用）。
     * 设置为当前活跃播放器，并自动播放。
     */
    fun onMusicPlayerOpened(packageName: String) {
        if (!musicPlayerManager.isMusicPlayer(packageName)) return

        musicPlayerManager.setActivePlayer(packageName)
        android.util.Log.d("MediaSkill", "打开音乐播放器，设置为活跃: $packageName")

        // 自动播放：延迟4秒发第一次播放键（等播放器初始化完成）
        mainHandler.postDelayed({
            mediaKeyDispatcher.dispatch(
                android.view.KeyEvent.KEYCODE_MEDIA_PLAY,
                packageName,
                musicPlayerManager.getAllPlayerPackages()
            )
        }, 4000)
        // 延迟5.5秒发第二次播放键（确保触发播放）
        mainHandler.postDelayed({
            mediaKeyDispatcher.dispatch(
                android.view.KeyEvent.KEYCODE_MEDIA_PLAY,
                packageName,
                musicPlayerManager.getAllPlayerPackages()
            )
        }, 5500)
        android.util.Log.d("MediaSkill", "已安排自动播放（4秒/5.5秒各发一次播放键）")
    }

    /**
     * [MusicFree 专用] 播放指定序号的搜索结果。
     * 已移除无障碍自动搜索，需要用户手动在播放器中选择。
     */
    fun selectSong(indexStr: String): ExecutionResult {
        val index = parseSongIndex(indexStr)
        if (index <= 0) return ExecutionResult(false, "请说第几首，比如第三首")
        return ExecutionResult(false, "请手动在播放器中选择第${indexStr}首歌曲")
    }

    /** 解析歌曲序号（支持"3"、"第三首"、"两首"等） */
    private fun parseSongIndex(str: String): Int {
        val clean = str.trim()
        // 纯数字
        clean.toIntOrNull()?.let { return it }
        // 中文数字
        val cnNum = mapOf(
            "一" to 1, "二" to 2, "两" to 2, "三" to 3, "四" to 4,
            "五" to 5, "六" to 6, "七" to 7, "八" to 8, "九" to 9, "十" to 10
        )
        for ((cn, num) in cnNum) {
            if (clean.contains(cn)) return num
        }
        return 0
    }
}
