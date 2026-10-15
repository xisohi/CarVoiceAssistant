package com.xisohi.car.voiceassistant.core.skill

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.xisohi.car.voiceassistant.core.ExecutionResult
import com.xisohi.car.voiceassistant.core.VoiceIntent

/**
 * 蓝牙/WiFi 控制 Skill
 *
 * 职责：蓝牙开关、WiFi 开关
 * 依赖：Context + BluetoothAdapter + WifiManager
 */
class BluetoothWifiSkill(private val context: Context) {

    private val wifiManager =
        context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

    fun execute(intent: VoiceIntent): ExecutionResult = when (intent.action) {
        "bt.on" -> setBluetooth(true)
        "bt.off" -> setBluetooth(false)
        "wifi.on" -> setWifi(true)
        "wifi.off" -> setWifi(false)
        else -> ExecutionResult(false, "不支持的蓝牙/WiFi 指令")
    }

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

    /**
     * WiFi 开关。
     *
     * ★ 修复：原实现调 WifiManager.setWifiEnabled()，但该方法自 API 29 起对 targetSdk >= Q 的
     * 应用**直接返回 false 且不抛异常**（只对系统应用/设备所有者生效）。本 App targetSdk = 34、
     * 主目标车机正是 Android 10 ⇒ 说"打开WiFi"永远得到"WiFi 操作失败"。
     * 改为拉起系统 WiFi 面板/设置页，由用户一键开关（Android 的设计就是不允许普通应用代改）。
     */
    private fun setWifi(on: Boolean): ExecutionResult {
        // 只读当前状态（ACCESS_WIFI_STATE 已声明，读取不受限）：已经是目标状态就不必弹面板
        val alreadyOn = try { wifiManager.isWifiEnabled } catch (_: Exception) { false }
        if (alreadyOn == on) {
            return ExecutionResult(true, if (on) "WiFi 本来就是开启的" else "WiFi 本来就是关闭的")
        }
        val want = if (on) "打开" else "关闭"
        return try {
            // Android 10+ 有专门的 WiFi 开关半屏面板；更低版本退到 WiFi 设置页
            val intent = if (Build.VERSION.SDK_INT >= 29) {
                Intent(android.provider.Settings.Panel.ACTION_WIFI)
            } else {
                Intent(android.provider.Settings.ACTION_WIFI_SETTINGS)
            }
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            ExecutionResult(true, "请在弹出的面板里$want WiFi")
        } catch (e: Exception) {
            // 部分车机 ROM 精简了 Settings 面板 → 退到 WiFi 设置页
            try {
                val fallback = Intent(android.provider.Settings.ACTION_WIFI_SETTINGS)
                fallback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(fallback)
                ExecutionResult(true, "请在 WiFi 设置里$want WiFi")
            } catch (e2: Exception) {
                ExecutionResult(false, "无法打开 WiFi 设置：${e2.message ?: "未知错误"}")
            }
        }
    }
}
