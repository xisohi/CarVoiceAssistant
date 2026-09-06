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
import android.text.TextUtils
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

    /** 通过媒体按键广播控制当前音乐（部分车机有效；生产环境建议接厂商媒体 SDK） */
    private fun mediaKey(keyCode: Int): ExecutionResult {
        val down = Intent(Intent.ACTION_MEDIA_BUTTON)
            .putExtra(Intent.EXTRA_KEY_EVENT, android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, keyCode))
        val up = Intent(Intent.ACTION_MEDIA_BUTTON)
            .putExtra(Intent.EXTRA_KEY_EVENT, android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, keyCode))
        context.sendBroadcast(down)
        context.sendBroadcast(up)
        return ExecutionResult(true, "好的")
    }

    // ---------- 导航 ----------

    private fun navigate(dest: String): ExecutionResult {
        if (dest.isBlank()) return ExecutionResult(false, "请告诉我目的地")
        val encoded = URLEncoder.encode(dest, "UTF-8")
        val geo = Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=$encoded"))
        geo.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(geo)
            ExecutionResult(true, "正在为您导航到$dest")
        } catch (e: Exception) {
            ExecutionResult(false, "未找到可用的导航应用")
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
            ExecutionResult(true, "空调已调到$degree度")
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
            if (ok) ExecutionResult(true, "已${if (open) "打开" else "关闭"}$position车窗")
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
