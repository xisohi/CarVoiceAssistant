package com.xisohi.car.voiceassistant.core

import android.Manifest
import android.app.Activity
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
import android.text.TextUtils
import android.content.ClipData
import android.content.ClipboardManager
import androidx.core.content.ContextCompat
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
            "ask.help" -> ExecutionResult(true, "可以试试说：把音量调到五十、播放音乐、导航去机场、打开空调、关闭车窗")
            "ask.weather" -> ExecutionResult(true, "离线模式下暂时查不了天气，建议联网后使用")
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
     * PLAY 操作：先启动音乐播放器，延迟 1.5 秒再发播放按键（等 MediaSession 初始化）。
     * PAUSE/NEXT/PREVIOUS：立即发送媒体按键。
     * 按键同时通过 dispatchMediaKeyEvent 和 ACTION_MEDIA_BUTTON 广播发送，增加成功率。
     */
    private fun mediaKey(keyCode: Int): ExecutionResult {
        val label = when (keyCode) {
            android.view.KeyEvent.KEYCODE_MEDIA_PLAY -> "正在打开音乐"
            android.view.KeyEvent.KEYCODE_MEDIA_PAUSE -> "已暂停"
            android.view.KeyEvent.KEYCODE_MEDIA_NEXT -> "下一首"
            android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS -> "上一首"
            else -> "好的"
        }
        return try {
            if (keyCode == android.view.KeyEvent.KEYCODE_MEDIA_PLAY) {
                // 启动播放器，延迟后再发播放按键（给 MediaSession 初始化时间）
                launchMusicPlayer()
                mainHandler.postDelayed({ dispatchMediaKey(keyCode) }, 2500)
            } else {
                dispatchMediaKey(keyCode)
            }
            ExecutionResult(true, label)
        } catch (e: Exception) {
            ExecutionResult(false, "音乐控制失败：${e.message ?: "未知错误"}")
        }
    }

    /** 发送媒体按键：dispatchMediaKeyEvent + 指定包名的 ACTION_MEDIA_BUTTON 广播 */
    private fun dispatchMediaKey(keyCode: Int) {
        val down = android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, keyCode)
        val up = android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, keyCode)
        try {
            audioManager.dispatchMediaKeyEvent(down)
            audioManager.dispatchMediaKeyEvent(up)
        } catch (_: Exception) {
        }
        // Android 9+ 不指定包名的媒体按键广播会被系统拦截，
        // 需要逐个指定常见播放器包名发送
        val packages = listOf(
            "fun.upup.musicfree",
            "com.netease.cloudmusic",
            "com.tencent.qqmusic",
            "com.kugou.android",
            "cn.kuwo.player"
        )
        for (pkg in packages) {
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

    /** 尝试启动音乐播放器：先默认播放器，再依次尝试常见播放器包名 */
    private fun launchMusicPlayer() {
        // 1. 系统默认音乐播放器
        try {
            val intent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_APP_MUSIC)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            return
        } catch (_: Exception) {
            // 没有默认播放器，继续尝试具体包名
        }

        // 2. 依次尝试常见音乐播放器包名
        val packages = listOf(
            "fun.upup.musicfree",          // MusicFree
            "com.netease.cloudmusic",      // 网易云音乐
            "com.tencent.qqmusic",         // QQ音乐
            "com.kugou.android",           // 酷狗音乐
            "cn.kuwo.player",              // 酷我音乐
            "com.android.music"            // 系统音乐
        )
        for (pkg in packages) {
            try {
                val intent = context.packageManager.getLaunchIntentForPackage(pkg)
                if (intent != null) {
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(intent)
                    return
                }
            } catch (_: Exception) {
                // 这个包名不存在，继续试下一个
            }
        }
        // 都没找到：忽略，dispatchMediaKeyEvent 仍可能控制后台播放器
    }

    // ---------- 导航 ----------

    /**
     * 拉起导航。依次尝试：
     * 1. 高德地图手机版 (androidamap://, com.autonavi.minimap)
     * 2. 高德地图车机版 (amapauto:// / androidamap://, com.autonavi.amapauto)
     * 3. 百度地图 (baidumap://, com.baidu.BaiduMap)
     * 4. 系统 geo: 通用协议（不指定包名，让系统选择）
     */
    private fun navigate(dest: String): ExecutionResult {
        if (dest.isBlank()) return ExecutionResult(false, "请告诉我目的地")
        val encoded = URLEncoder.encode(dest, "UTF-8")

        // 1. 高德地图手机版
        val amapMobile = Intent(Intent.ACTION_VIEW).apply {
            data = Uri.parse("androidamap://route?sourceApplication=voiceassistant&dname=$encoded&dev=0&t=0")
            setPackage("com.autonavi.minimap")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (tryStartActivity(amapMobile)) {
            return ExecutionResult(true, "正在用高德地图导航到${dest}")
        }

        // 2. 高德地图车机版（androidauto:// URI，poi 搜索可打开搜索页）
        // 设置待自动输入的目的地（无障碍服务会在搜索页打开后自动填入并搜索）
        AutoInputService.setPendingDestination(dest)
        // 同时复制到剪贴板作为兜底
        copyToClipboard("导航目的地", dest)
        val amapAutoIntents = listOf(
            // poi 搜索（已验证可打开搜索页；同时通过 extra 尝试传关键词，部分版本可能支持）
            Intent(Intent.ACTION_VIEW).apply {
                data = Uri.parse("androidauto://poi?keyword=$encoded")
                setPackage("com.autonavi.amapauto")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra("keyword", dest)
                putExtra("query", dest)
                putExtra("search", dest)
                putExtra(Intent.EXTRA_TEXT, dest)
            },
            // 导航（多种参数名备选，可能在某些版本可用）
            Intent(Intent.ACTION_VIEW).apply {
                data = Uri.parse("androidauto://navi?to=$encoded")
                setPackage("com.autonavi.amapauto")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            },
            Intent(Intent.ACTION_VIEW).apply {
                data = Uri.parse("androidauto://navi?destination=$encoded")
                setPackage("com.autonavi.amapauto")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            },
            Intent(Intent.ACTION_VIEW).apply {
                data = Uri.parse("androidauto://navigation?destination=$encoded")
                setPackage("com.autonavi.amapauto")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            },
            Intent(Intent.ACTION_VIEW).apply {
                data = Uri.parse("androidauto://route?destination=$encoded")
                setPackage("com.autonavi.amapauto")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        )
        for (intent in amapAutoIntents) {
            if (tryStartActivity(intent)) {
                return ExecutionResult(true, "正在为您搜索${dest}")
            }
        }

        // 3. 百度地图
        val baidu = Intent(Intent.ACTION_VIEW).apply {
            data = Uri.parse("baidumap://map/direction?destination=$encoded&mode=driving&src=voiceassistant")
            setPackage("com.baidu.BaiduMap")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (tryStartActivity(baidu)) {
            return ExecutionResult(true, "正在用百度地图导航到${dest}")
        }

        // 4. 回退：系统 geo: 协议（不指定包名，让系统选择能处理的地图应用）
        val geo = Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=$encoded")).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (tryStartActivity(geo)) {
            return ExecutionResult(true, "正在为您导航到${dest}")
        }

        // 5. 最终兜底：直接启动高德车机版主界面（车机版可能不支持标准 URI 协议）
        return try {
            val launchIntent = context.packageManager.getLaunchIntentForPackage("com.autonavi.amapauto")
            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(launchIntent)
                ExecutionResult(true, "已打开高德地图，请手动输入目的地：${dest}")
            } else {
                ExecutionResult(false, "未找到可用的导航应用，请先安装高德或百度地图")
            }
        } catch (e: Exception) {
            ExecutionResult(false, "未找到可用的导航应用，请先安装高德或百度地图")
        }
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

    /** 复制文本到剪贴板 */
    private fun copyToClipboard(label: String, text: String) {
        try {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
        } catch (_: Exception) {
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
}
