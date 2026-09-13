package com.xisohi.car.voiceassistant.core

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.content.ClipData
import android.content.ClipboardManager
import androidx.core.content.ContextCompat
// 无障碍服务已移除，导航使用 URI scheme，音乐控制使用媒体按键
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 执行结果：spoken 为要向用户播报的文案 */
data class ExecutionResult(val handled: Boolean, val spoken: String)

/**
 * 车控执行层。
 *
 * 系统级功能（音量/音乐/WiFi/蓝牙/导航拉起）直接实现；
 * 空调、车窗等"真正的车控"需要接入车机厂商 SDK/广播——
 * 通过 [CarControlProvider] 接口扩展，默认实现为占位提示。
 */
class SkillExecutor(private val context: Context) {

    /** 车控扩展接口：接入厂商 SDK 时实现并注入 */
    interface CarControlProvider {
        fun setClimate(on: Boolean): Boolean = false
        fun setTemperature(degree: Int): Boolean = false
        fun openWindow(position: String): Boolean = false
        fun closeWindow(position: String): Boolean = false
    }

    var carControlProvider: CarControlProvider? = null

    // 音乐播放器优先级列表（从高到低）
    private val musicPlayerPackages = listOf(
        "fun.upup.musicfree",        // MusicFree（支持自动搜索播放）
        "com.netease.cloudmusic",    // 网易云音乐
        "com.tencent.qqmusic",       // QQ音乐
        "com.kugou.android",         // 酷狗音乐
        "cn.kuwo.player"             // 酷我音乐
    )

    // 当前活跃的音乐播放器包名（null=未设置，按优先级选择）
    @Volatile
    private var currentMusicPlayer: String? = null

    /**
     * 获取当前应该使用的音乐播放器。
     * 优先使用已记录的活跃播放器；如果未设置，按优先级选择第一个已安装的。
     */
    private fun getActiveMusicPlayer(): String? {
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
    fun setActiveMusicPlayer(packageName: String) {
        if (isAppInstalled(packageName)) {
            currentMusicPlayer = packageName
            android.util.Log.d("SkillExecutor", "当前活跃音乐播放器: $packageName")
        }
    }

    private val audioManager =
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val wifiManager =
        context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

    fun execute(intent: VoiceIntent): ExecutionResult = try {
        when (intent.action) {
            "volume.set" -> setVolume(intent.params["value"])
            "volume.up" -> adjustVolume(true)
            "volume.down" -> adjustVolume(false)
            "volume.mute" -> setMute(true)
            "media.play" -> mediaKey(android.view.KeyEvent.KEYCODE_MEDIA_PLAY)
            "media.pause" -> mediaKey(android.view.KeyEvent.KEYCODE_MEDIA_PAUSE)
            "media.next" -> mediaKey(android.view.KeyEvent.KEYCODE_MEDIA_NEXT)
            "media.prev" -> mediaKey(android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS)
            "nav.to" -> navigate(intent.params["dest"] ?: "")
            "climate.on" -> climateOn()
            "climate.off" -> climateOff()
            "climate.temp" -> climateTemp(intent.params["value"] ?: "")
            "window.open" -> window(true, intent.params["position"] ?: "全部")
            "window.close" -> window(false, intent.params["position"] ?: "全部")
            "bt.on" -> setBluetooth(true)
            "bt.off" -> setBluetooth(false)
            "wifi.on" -> setWifi(true)
            "wifi.off" -> setWifi(false)
            "ask.time" -> ExecutionResult(true, "现在是" + SimpleDateFormat("HH点mm分", Locale.CHINA).format(Date()))
            "ask.help" -> ExecutionResult(true, "可以试试说：把音量调到五十、打开音乐、播放、暂停、下一首、导航去牛圩村、打开空调")
            "ask.weather" -> ExecutionResult(true, "离线模式下暂时查不了天气，建议联网后使用")
            "app.open" -> openApp(intent.params["app"] ?: "")
            else -> ExecutionResult(false, "这个指令我还不支持")
        }
    } catch (e: Exception) {
        ExecutionResult(false, "执行失败：${e.message ?: "未知错误"}")
    }

    // ---------- 音量 ----------

    private fun setVolume(raw: String?): ExecutionResult {
        val value = raw?.replace("百分之", "")?.replace("%", "")?.toIntOrNull()
        if (value == null) return ExecutionResult(false, "没听清音量数值")
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val target = (value.coerceIn(0, 100) * max / 100).coerceIn(0, max)
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
        return ExecutionResult(true, "音量已调到$value")
    }

    private fun adjustVolume(up: Boolean): ExecutionResult {
        audioManager.adjustStreamVolume(
            AudioManager.STREAM_MUSIC,
            if (up) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER,
            0
        )
        return ExecutionResult(true, if (up) "音量已调大" else "音量已调小")
    }

    private fun setMute(on: Boolean): ExecutionResult {
        audioManager.adjustStreamVolume(
            AudioManager.STREAM_MUSIC,
            AudioManager.ADJUST_MUTE,
            0
        )
        return ExecutionResult(true, "已静音")
    }

    // ---------- 媒体 ----------

    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * 音乐控制。
     * 所有操作（播放/暂停/下一首/上一首）都立即发送媒体按键，不启动播放器。
     * 启动播放器的指令是"打开音乐"、"打开播放器"等（走 app.open 意图）。
     *
     * 注意："播放"是指播放器已打开后的播放/继续播放动作，不是启动播放器。
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
            val playerPkg = getActiveMusicPlayer()
            if (playerPkg == null) {
                return ExecutionResult(false, "未安装任何音乐播放器，请先说打开音乐")
            }
            // 记录当前活跃播放器
            currentMusicPlayer = playerPkg
            // 所有操作都直接发媒体按键（播放/暂停/下一首/上一首）
            // 不启动播放器，启动播放器走 app.open 意图（"打开音乐"、"打开播放器"）
            dispatchMediaKey(keyCode, playerPkg)
            ExecutionResult(true, label)
        } catch (e: Exception) {
            ExecutionResult(false, "音乐控制失败：${e.message ?: "未知错误"}")
        }
    }

    /**
     * 启动指定包名的播放器（带到前台，确保 Service 运行）。
     */
    private fun launchPlayerByPackage(packageName: String) {
        try {
            val intent = context.packageManager.getLaunchIntentForPackage(packageName)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                context.startActivity(intent)
                android.util.Log.d("SkillExecutor", "已启动播放器: $packageName")
            }
        } catch (e: Exception) {
            android.util.Log.w("SkillExecutor", "启动播放器失败: $packageName, ${e.message}")
        }
    }

    /**
     * 发送媒体按键。
     *
     * 三层策略（按优先级）：
     * 1. [MusicFree 专用] 直接发送给 react-native-track-player 的 MusicService（最可靠）
     * 2. [通用] AudioManager.dispatchMediaKeyEvent（系统级媒体按键）
     * 3. [通用] 逐个指定常见播放器包名发送 ACTION_MEDIA_BUTTON 广播
     *
     * 修改 MusicFree 相关逻辑时，只改第 1 层，不要影响第 2、3 层的通用逻辑。
     */
    private fun dispatchMediaKey(keyCode: Int, targetPackage: String? = null) {
        val down = android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, keyCode)
        val up = android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, keyCode)

        // 1. 系统级媒体按键（发送给当前活跃的 MediaSession，最可靠）
        try {
            audioManager.dispatchMediaKeyEvent(down)
            audioManager.dispatchMediaKeyEvent(up)
            android.util.Log.d("SkillExecutor", "已发送系统级媒体按键: $keyCode, 目标: ${targetPackage ?: "系统默认"}")
        } catch (e: Exception) {
            android.util.Log.w("SkillExecutor", "dispatchMediaKeyEvent 失败: ${e.message}")
        }

        // 2. 发送给目标播放器（指定包名的广播）
        targetPackage?.let { pkg ->
            try {
                val downIntent = Intent(Intent.ACTION_MEDIA_BUTTON)
                    .setPackage(pkg)
                    .putExtra(Intent.EXTRA_KEY_EVENT, down)
                val upIntent = Intent(Intent.ACTION_MEDIA_BUTTON)
                    .setPackage(pkg)
                    .putExtra(Intent.EXTRA_KEY_EVENT, up)
                context.sendBroadcast(downIntent)
                context.sendBroadcast(upIntent)
                android.util.Log.d("SkillExecutor", "已发送媒体按键广播给: $pkg")
            } catch (e: Exception) {
                android.util.Log.w("SkillExecutor", "发送广播给 $pkg 失败: ${e.message}")
            }
        }

        // 3. 兜底：逐个指定所有播放器包名发送广播（确保至少有一个响应）
        for (pkg in musicPlayerPackages) {
            if (pkg == targetPackage) continue // 已经发过了，跳过
            try {
                val downIntent = Intent(Intent.ACTION_MEDIA_BUTTON)
                    .setPackage(pkg)
                    .putExtra(Intent.EXTRA_KEY_EVENT, down)
                val upIntent = Intent(Intent.ACTION_MEDIA_BUTTON)
                    .setPackage(pkg)
                    .putExtra(Intent.EXTRA_KEY_EVENT, up)
                context.sendBroadcast(downIntent)
                context.sendBroadcast(upIntent)
            } catch (_: Exception) {
            }
        }
    }

    /**
     * 启动音乐播放器。
     *
     * 优先级：
     * 1. [MusicFree 专用] fun.upup.musicfree（用户已安装，控制最可靠）
     * 2. [通用] 系统默认音乐播放器
     * 3. [通用] 网易云 / QQ音乐 / 酷狗 / 酷我 / 系统音乐
     *
     * 修改 MusicFree 相关逻辑时，只改第 1 层。
     */
    private fun launchMusicPlayer() {
        val playerPkg = getActiveMusicPlayer()
        if (playerPkg != null) {
            currentMusicPlayer = playerPkg
            launchPlayerByPackage(playerPkg)
            return
        }
        // 兜底：系统默认音乐播放器
        try {
            val intent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_APP_MUSIC)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            android.util.Log.d("SkillExecutor", "已启动系统默认音乐播放器")
        } catch (_: Exception) {
        }
    }

    /**
     * [MusicFree 专用] 播放指定序号的搜索结果。
     *
     * @param indexStr 序号字符串（如"3"、"第三首"）
     * @return 执行结果
     */
    fun selectSong(indexStr: String): ExecutionResult {
        val index = parseSongIndex(indexStr)
        if (index <= 0) return ExecutionResult(false, "请说第几首，比如第三首")
        // 已移除无障碍自动搜索，需要用户手动在播放器中选择
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

// ---------- 导航 ----------

    /**
     * 拉起导航。
     *
     * 优先级：
     * 1. [高德车机版] androidauto://, com.autonavi.amapauto
     *    - 优先 keywordNavi（搜索+导航一体化）
     *    - 次选 poi（打开搜索页）
     * 2. [高德手机版] androidamap://, com.autonavi.minimap
     * 3. [百度地图汽车版] baidumap://, com.baidu.naviauto
     * 4. [通用] 系统 geo: 协议
     *
     * 修复：修正高德车机版 URI 参数格式，增加 pending 状态清理
     */
    private fun navigate(dest: String): ExecutionResult {
        if (dest.isBlank()) return ExecutionResult(false, "请告诉我目的地")
        val encoded = URLEncoder.encode(dest, "UTF-8")

        // 1. 高德车机版（优先）- 使用 URI scheme，不需要无障碍服务
        if (isAppInstalled("com.autonavi.amapauto")) {
            // 方式1：keywordNavi（搜索+导航）
            val keywordIntent = Intent(Intent.ACTION_VIEW).apply {
                data = Uri.parse(
                    "androidauto://keywordNavi?" +
                            "sourceApplication=${context.packageName}" +
                            "&keywords=$encoded" +
                            "&style=2"
                )
                setPackage("com.autonavi.amapauto")
                addCategory("android.intent.category.DEFAULT")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (tryStartActivity(keywordIntent)) {
                return ExecutionResult(true, "正在为您导航到${dest}")
            }

            // 方式2：poi 搜索（打开搜索页，显示结果列表）
            val poiIntent = Intent(Intent.ACTION_VIEW).apply {
                data = Uri.parse(
                    "androidauto://poi?" +
                            "sourceApplication=${context.packageName}" +
                            "&keywords=$encoded"
                )
                setPackage("com.autonavi.amapauto")
                addCategory("android.intent.category.DEFAULT")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (tryStartActivity(poiIntent)) {
                return ExecutionResult(true, "正在搜索${dest}")
            }

            // 方式3：直接启动高德主界面
            val launchIntent = context.packageManager.getLaunchIntentForPackage("com.autonavi.amapauto")
            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(launchIntent)
                return ExecutionResult(true, "已打开高德地图，请手动搜索${dest}")
            }
        }

        // 2. 高德手机版
        val amapMobile = Intent(Intent.ACTION_VIEW).apply {
            data = Uri.parse(
                "androidamap://route?" +
                        "sourceApplication=voiceassistant" +
                        "&dname=$encoded" +
                        "&dev=0&t=0"
            )
            setPackage("com.autonavi.minimap")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (tryStartActivity(amapMobile)) {
            return ExecutionResult(true, "正在用高德地图导航到${dest}")
        }

        // 3. 百度地图汽车版
        if (isAppInstalled("com.baidu.naviauto")) {
            val baiduIntents = listOf(
                Intent(Intent.ACTION_VIEW).apply {
                    data = Uri.parse(
                        "baidumap://map/direction?" +
                                "destination=$encoded" +
                                "&mode=driving" +
                                "&src=voiceassistant"
                    )
                    setPackage("com.baidu.naviauto")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                },
                Intent(Intent.ACTION_VIEW).apply {
                    data = Uri.parse(
                        "baidumap://map/search?" +
                                "query=$encoded" +
                                "&src=voiceassistant"
                    )
                    setPackage("com.baidu.naviauto")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )

            for (intent in baiduIntents) {
                if (tryStartActivity(intent)) {
                    return ExecutionResult(true, "正在用百度地图搜索${dest}")
                }
            }
        }

        // 4. 通用 geo: Intent（系统会选择默认地图应用）
        val geoIntent = Intent(Intent.ACTION_VIEW).apply {
            data = Uri.parse("geo:0,0?q=$encoded")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (tryStartActivity(geoIntent)) {
            return ExecutionResult(true, "正在搜索${dest}，请选择导航")
        }

        // 全部失败
        return ExecutionResult(false, "未找到可用的导航应用")
    }

    /** 尝试启动 Activity，成功返回 true；没有能处理的应用时返回 false */
    private fun tryStartActivity(intent: Intent): Boolean {
        return try {
            // 先检查是否有能处理的 Activity
            val resolveInfo = context.packageManager.resolveActivity(intent, 0)
            if (resolveInfo == null) {
                android.util.Log.w("SkillExecutor", "无应用处理: ${intent.data} (pkg=${intent.`package`})")
                return false
            }
            android.util.Log.d("SkillExecutor", "拉起: ${intent.data} -> ${resolveInfo.activityInfo.packageName}/${resolveInfo.activityInfo.name}")
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            android.util.Log.w("SkillExecutor", "启动失败: ${intent.data}, 错误: ${e.message}")
            false
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

    /** 复制文本到剪贴板 */
    private fun copyToClipboard(label: String, text: String) {
        try {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
        } catch (_: Exception) {
        }
    }

    // ---------- 打开应用 ----------

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
                // 如果打开的是音乐播放器，设置为当前活跃播放器，并自动播放
                if (packageName in musicPlayerPackages) {
                    currentMusicPlayer = packageName
                    android.util.Log.d("SkillExecutor", "打开音乐播放器，设置为活跃: $packageName")
                    // 自动播放：延迟4秒发第一次播放键（等播放器初始化完成）
                    mainHandler.postDelayed({ dispatchMediaKey(android.view.KeyEvent.KEYCODE_MEDIA_PLAY, packageName) }, 4000)
                    // 延迟5.5秒发第二次播放键（确保触发播放）
                    mainHandler.postDelayed({ dispatchMediaKey(android.view.KeyEvent.KEYCODE_MEDIA_PLAY, packageName) }, 5500)
                    android.util.Log.d("SkillExecutor", "已安排自动播放（4秒/5.5秒各发一次播放键）")
                }
                ExecutionResult(true, "已打开$displayName")
            } else {
                ExecutionResult(false, "未安装$displayName")
            }
        } catch (e: Exception) {
            ExecutionResult(false, "打开$displayName 失败：${e.message}")
        }
    }

    // ---------- 车控（厂商 SDK 扩展点） ----------

    private fun climateOn(): ExecutionResult {
        val provider = carControlProvider
        return if (provider != null && provider.setClimate(true)) {
            ExecutionResult(true, "空调已开启")
        } else {
            ExecutionResult(
                false,
                "空调控制需要接入车机厂商 SDK，请实现 CarControlProvider 接口"
            )
        }
    }

    private fun climateOff(): ExecutionResult {
        val provider = carControlProvider
        return if (provider != null && provider.setClimate(false)) {
            ExecutionResult(true, "空调已关闭")
        } else {
            ExecutionResult(
                false,
                "空调控制需要接入车机厂商 SDK，请实现 CarControlProvider 接口"
            )
        }
    }

    private fun climateTemp(raw: String): ExecutionResult {
        val degree = raw.replace("度", "").toIntOrNull()
        if (degree == null) return ExecutionResult(false, "没听清温度数值")
        val provider = carControlProvider
        return if (provider != null && provider.setTemperature(degree)) {
            ExecutionResult(true, "空调已调到${degree}度")
        } else {
            ExecutionResult(
                false,
                "空调温度控制需要接入车机厂商 SDK，请实现 CarControlProvider 接口"
            )
        }
    }

    private fun window(open: Boolean, position: String): ExecutionResult {
        val provider = carControlProvider
        return if (provider != null) {
            val ok = if (open) provider.openWindow(position) else provider.closeWindow(position)
            if (ok) ExecutionResult(true, "已${if (open) "打开" else "关闭"}${position}车窗")
            else ExecutionResult(false, "车窗控制失败")
        } else {
            ExecutionResult(
                false,
                "车窗控制需要接入车机厂商 SDK，请实现 CarControlProvider 接口"
            )
        }
    }

    // ---------- 蓝牙 / WiFi ----------

    private fun setBluetooth(on: Boolean): ExecutionResult {
        if (Build.VERSION.SDK_INT >= 31 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return ExecutionResult(false, "缺少蓝牙权限")
        }
        val adapter = BluetoothAdapter.getDefaultAdapter()
            ?: return ExecutionResult(false, "本机没有蓝牙")
        return try {
            if (on) adapter.enable() else adapter.disable()
            ExecutionResult(true, if (on) "蓝牙已开启" else "蓝牙已关闭")
        } catch (e: Exception) {
            ExecutionResult(false, "蓝牙操作失败：${e.message}")
        }
    }

    @Suppress("DEPRECATION")
    private fun setWifi(on: Boolean): ExecutionResult {
        val ok = wifiManager.setWifiEnabled(on)
        return ExecutionResult(ok, if (ok) "WiFi 已${if (on) "开启" else "关闭"}" else "WiFi 操作失败")
    }

    // ---------- 天气查询 ----------

    /**
     * 通过IP定位获取当前城市（不需要定位权限，只要联网就能用）
     * 使用 ipapi.co 免费API，支持HTTPS
     * 失败时返回 null，调用方应回退到默认城市
     */
    private fun getCurrentCityByIp(): String? {
        return try {
            val url = java.net.URL("https://ipapi.co/json/")
            val conn = url.openConnection() as java.net.HttpURLConnection
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            conn.requestMethod = "GET"
            conn.setRequestProperty("User-Agent", "Mozilla/5.0")

            if (conn.responseCode != 200) return null

            val json = conn.inputStream.bufferedReader().use { it.readText() }
            conn.disconnect()

            val obj = org.json.JSONObject(json)
            // ipapi.co 返回格式: {"city": "蚌埠", "region": "安徽", "country": "CN", ...}
            val city = obj.optString("city", "")
            val region = obj.optString("region", "")
            when {
                city.isNotBlank() && city != "null" -> city
                region.isNotBlank() && region != "null" -> region
                else -> null
            }
        } catch (e: Exception) {
            android.util.Log.w("SkillExecutor", "IP定位失败: ${e.message}")
            null
        }
    }

    /**
     * 查询天气（在后台线程调用，不能在主线程执行）
     * @param city 指定城市，传 null 或空字符串时自动IP定位
     * @param timeIndex 时间索引：0=今天，1=明天，2=后天
     * @return 天气播报文本；定位失败时返回 null（让上层提示用户说城市名）
     * 使用 wttr.in 免费API，不需要注册账号
     * API: https://wttr.in/城市名?format=j1
     */
    fun queryWeather(city: String? = null, timeIndex: Int = 0): String? {
        // 确定查询的城市
        val targetCity = when {
            !city.isNullOrBlank() -> city
            else -> {
                // 自动IP定位
                val located = getCurrentCityByIp()
                if (located == null) {
                    android.util.Log.w("SkillExecutor", "IP定位失败，返回null让上层提示用户")
                    return null
                }
                located
            }
        }
        android.util.Log.d("SkillExecutor", "天气查询城市: $targetCity")

        return try {
            val url = java.net.URL("https://wttr.in/${java.net.URLEncoder.encode(targetCity, "UTF-8")}?format=j1&lang=zh")
            val conn = url.openConnection() as java.net.HttpURLConnection
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            conn.requestMethod = "GET"
            conn.setRequestProperty("User-Agent", "curl/7.68.0")

            val responseCode = conn.responseCode
            if (responseCode != 200) {
                return "天气查询失败，服务器返回 $responseCode"
            }

            val inputStream = conn.inputStream
            val json = inputStream.bufferedReader().use { it.readText() }
            inputStream.close()
            conn.disconnect()

            parseWeatherJson(json, targetCity, timeIndex)
        } catch (e: java.net.SocketTimeoutException) {
            "天气查询超时，请检查网络连接"
        } catch (e: java.net.UnknownHostException) {
            "网络不可用，请连接网络后再试"
        } catch (e: Exception) {
            "天气查询失败：${e.message ?: "未知错误"}"
        }
    }

    /**
     * 英文天气描述 → 中文映射表
     * 确保所有天气描述都翻译成中文，不依赖 wttr.in 的中文翻译
     */
    private val weatherDescMap = mapOf(
        "Sunny" to "晴",
        "Clear" to "晴朗",
        "Partly cloudy" to "局部多云",
        "Cloudy" to "多云",
        "Overcast" to "阴天",
        "Mist" to "薄雾",
        "Fog" to "雾",
        "Freezing fog" to "冻雾",
        "Patchy rain possible" to "可能有零星小雨",
        "Patchy rain nearby" to "附近有零星小雨",
        "Patchy light rain" to "零星小雨",
        "Light rain" to "小雨",
        "Moderate rain at times" to "间歇性中雨",
        "Moderate rain" to "中雨",
        "Heavy rain at times" to "间歇性大雨",
        "Heavy rain" to "大雨",
        "Light freezing rain" to "小冻雨",
        "Moderate or heavy freezing rain" to "中到大冻雨",
        "Light sleet" to "小雨夹雪",
        "Moderate or heavy sleet" to "中到大雨夹雪",
        "Patchy light snow" to "零星小雪",
        "Light snow" to "小雪",
        "Patchy moderate snow" to "零星中雪",
        "Moderate snow" to "中雪",
        "Patchy heavy snow" to "零星大雪",
        "Heavy snow" to "大雪",
        "Ice pellets" to "冰粒",
        "Light rain shower" to "小阵雨",
        "Moderate or heavy rain shower" to "中到大阵雨",
        "Torrential rain shower" to "暴雨",
        "Light sleet showers" to "小阵雨夹雪",
        "Moderate or heavy sleet showers" to "中到大阵雨夹雪",
        "Light snow showers" to "小阵雪",
        "Moderate or heavy snow showers" to "中到大阵雪",
        "Light showers of ice pellets" to "小阵冰粒",
        "Moderate or heavy showers of ice pellets" to "中到大阵冰粒",
        "Patchy light rain with thunder" to "零星小雷雨",
        "Moderate or heavy rain with thunder" to "中到大雷雨",
        "Patchy light snow with thunder" to "零星小雷雪",
        "Moderate or heavy snow with thunder" to "中到大雷雪",
        "Thundery outbreaks possible" to "可能有雷阵雨",
        "Blowing snow" to "吹雪",
        "Blizzard" to "暴风雪"
    )

    /**
     * 时间词列表
     */
    private val timeWords = listOf(
        "今日", "今天", "明日", "明天", "后天", "大后天",
        "昨日", "昨天", "前天", "大前天",
        "现在", "当前", "目前", "此刻",
        "这周", "本周", "下周", "下星期",
        "这个月", "本月", "下个月", "下月",
        "今年", "本年", "明年", "来年"
    )

    /**
     * 判断字符串是否只包含时间词（没有城市名）
     * 比如"明天"、"今天" → true
     * "明天蚌埠"、"北京" → false
     */
    fun isOnlyTimeWord(word: String?): Boolean {
        if (word.isNullOrBlank()) return false
        // 去掉所有时间词后，如果为空说明只包含时间词
        var result: String = word
        for (tw in timeWords) {
            result = result.replace(tw, "")
        }
        return result.isBlank()
    }

    /**
     * 从包含时间词的字符串中提取城市名
     * 比如"明天蚌埠" → "蚌埠"，"今天北京" → "北京"
     * 如果只包含时间词，返回 null
     */
    fun extractCity(word: String?): String? {
        if (word.isNullOrBlank()) return null
        var result: String = word
        // 去掉所有时间词
        for (tw in timeWords) {
            result = result.replace(tw, "")
        }
        result = result.trim()
        return if (result.isNotBlank()) result else null
    }

    /**
     * 从文本中提取时间索引
     * 0=今天，1=明天，2=后天，-1=未指定（默认今天）
     */
    fun getTimeIndex(text: String?): Int {
        if (text.isNullOrBlank()) return 0
        return when {
            text.contains("后天") || text.contains("大后天") -> 2
            text.contains("明天") || text.contains("明日") -> 1
            else -> 0  // 今天、今日、未指定都默认今天
        }
    }

    /**
     * 把英文天气描述翻译成中文
     */
    private fun translateWeatherDesc(english: String): String {
        val trimmed = english.trim()
        return weatherDescMap[trimmed] ?: run {
            // 尝试模糊匹配（忽略大小写）
            val lower = trimmed.lowercase()
            weatherDescMap.entries.firstOrNull { it.key.lowercase() == lower }?.value
                ?: run {
                    android.util.Log.w("SkillExecutor", "未翻译的天气描述: $english")
                    trimmed // 找不到映射时返回原文（但应该不会出现）
                }
        }
    }

    /**
     * 解析 wttr.in 返回的 JSON
     * @param timeIndex 时间索引：0=今天，1=明天，2=后天
     */
    private fun parseWeatherJson(json: String, city: String, timeIndex: Int = 0): String {
        return try {
            val obj = org.json.JSONObject(json)
            val weatherArray = obj.getJSONArray("weather")

            // 确保 timeIndex 在有效范围内
            val idx = timeIndex.coerceIn(0, weatherArray.length() - 1)
            val dayWeather = weatherArray.getJSONObject(idx)

            // 日期描述
            val dayLabel = when (idx) {
                0 -> "今天"
                1 -> "明天"
                2 -> "后天"
                else -> "第${idx + 1}天"
            }

            // 当天的天气描述（取中午12点的天气，最有代表性）
            val hourly = dayWeather.getJSONArray("hourly")
            val midday = hourly.getJSONObject((hourly.length() - 1) / 2)  // 取中间时段
            val weatherEn = midday.getJSONArray("weatherDesc").getJSONObject(0).getString("value")
            val weatherDesc = translateWeatherDesc(weatherEn)

            // 温度范围
            val maxTemp = dayWeather.getString("maxtempC")
            val minTemp = dayWeather.getString("mintempC")

            // 如果是今天，额外显示当前温度、体感、湿度、风速
            val sb = StringBuilder()
            if (idx == 0) {
                val current = obj.getJSONArray("current_condition").getJSONObject(0)
                val tempC = current.getString("temp_C")
                val feelsLike = current.getString("FeelsLikeC")
                val humidity = current.getString("humidity")
                val windSpeed = current.getString("windspeedKmph")

                sb.append("${city}今天${weatherDesc}，")
                sb.append("当前温度${tempC}度，体感${feelsLike}度，")
                sb.append("今天${minTemp}到${maxTemp}度，")
                sb.append("湿度${humidity}%，风速${windSpeed}公里每小时")
            } else {
                // 明天/后天：只显示天气描述和温度范围
                sb.append("${city}${dayLabel}${weatherDesc}，")
                sb.append("温度${minTemp}到${maxTemp}度")
            }

            sb.toString()
        } catch (e: Exception) {
            "天气数据解析失败：${e.message ?: "未知错误"}"
        }
    }
}
