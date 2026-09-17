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

    /** 自动播放的延迟任务列表（用于取消） */
    private val autoPlayRunnables = mutableListOf<Runnable>()

    fun execute(intent: VoiceIntent): ExecutionResult = when (intent.action) {
        // "播放音乐"：如果播放器没打开，先打开再播放；如果已打开，直接播放
        "media.play" -> ensurePlayerAndPlay()
        // 暂停/下一首/上一首时，取消待执行的自动播放任务
        // 场景：用户说"播放音乐"→ 打开播放器 + 延迟4秒发播放键；4秒内用户说"暂停"
        // 如果不取消，4秒后播放键还会发，导致暂停后又自动播放
        "media.pause" -> {
            cancelAutoPlay()
            mediaKey(android.view.KeyEvent.KEYCODE_MEDIA_PAUSE)
        }
        "media.next" -> {
            cancelAutoPlay()
            mediaKey(android.view.KeyEvent.KEYCODE_MEDIA_NEXT)
        }
        "media.prev" -> {
            cancelAutoPlay()
            mediaKey(android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS)
        }
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
     * 只设置当前活跃播放器，不自动播放。
     * 由 AppLaunchSkill 在"打开音乐"（app.open）时调用。
     * 用户说"打开音乐"→ 只打开播放器，不自动播放。
     */
    fun setActivePlayer(packageName: String) {
        if (musicPlayerManager.isMusicPlayer(packageName)) {
            // 先取消之前的自动播放延迟任务（避免切换播放器后旧任务还在执行）
            cancelAutoPlay()
            musicPlayerManager.setActivePlayer(packageName)
            android.util.Log.d("MediaSkill", "打开音乐播放器，设置为活跃（不自动播放）: $packageName")
        }
    }

    /**
     * 确保播放器已打开并播放。
     * 如果播放器没打开，先打开再延迟发送播放键；如果已打开，直接发送播放键。
     * 由"播放音乐"（media.play）调用。
     */
    private fun ensurePlayerAndPlay(): ExecutionResult {
        val playerPkg = musicPlayerManager.getActivePlayer()
        return if (playerPkg == null) {
            // 没有活跃播放器，先打开播放器
            android.util.Log.d("MediaSkill", "播放音乐：播放器未打开，先启动播放器")
            musicPlayerManager.launchMusicPlayer()
            // launchMusicPlayer() 内部会设置活跃播放器，这里再获取一次
            val openedPkg = musicPlayerManager.getActivePlayer()
            if (openedPkg != null) {
                // 打开后延迟发送播放键（等播放器初始化完成）
                scheduleAutoPlay(openedPkg)
                ExecutionResult(true, "正在打开音乐播放器")
            } else {
                ExecutionResult(false, "未安装任何音乐播放器")
            }
        } else {
            // 有活跃播放器，直接发送播放键
            android.util.Log.d("MediaSkill", "播放音乐：播放器已打开，直接发送播放键")
            musicPlayerManager.setActivePlayer(playerPkg)
            mediaKeyDispatcher.dispatch(
                android.view.KeyEvent.KEYCODE_MEDIA_PLAY,
                playerPkg,
                musicPlayerManager.getAllPlayerPackages()
            )
            ExecutionResult(true, "继续播放")
        }
    }

    /**
     * 安排自动播放：延迟4秒/5.5秒各发一次播放键。
     * 复用 onMusicPlayerOpened() 和 ensurePlayerAndPlay() 的逻辑。
     */
    private fun scheduleAutoPlay(packageName: String) {
        cancelAutoPlay()
        val r1 = Runnable {
            mediaKeyDispatcher.dispatch(
                android.view.KeyEvent.KEYCODE_MEDIA_PLAY,
                packageName,
                musicPlayerManager.getAllPlayerPackages()
            )
        }
        val r2 = Runnable {
            mediaKeyDispatcher.dispatch(
                android.view.KeyEvent.KEYCODE_MEDIA_PLAY,
                packageName,
                musicPlayerManager.getAllPlayerPackages()
            )
        }
        autoPlayRunnables.add(r1)
        autoPlayRunnables.add(r2)
        mainHandler.postDelayed(r1, 4000)
        mainHandler.postDelayed(r2, 5500)
        android.util.Log.d("MediaSkill", "已安排自动播放（4秒/5.5秒各发一次播放键）")
    }

    /**
     * 打开音乐播放器时的回调（由 AppLaunchSkill 或 SkillExecutor 调用）。
     * 设置为当前活跃播放器，并自动播放。
     */
    /**
     * 取消所有待执行的自动播放延迟任务。
     * 在切换播放器、暂停、onDestroy 时调用。
     */
    fun cancelAutoPlay() {
        if (autoPlayRunnables.isNotEmpty()) {
            autoPlayRunnables.forEach { mainHandler.removeCallbacks(it) }
            autoPlayRunnables.clear()
            android.util.Log.d("MediaSkill", "已取消所有自动播放延迟任务")
        }
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
