package com.xisohi.car.voiceassistant.core.skill

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Context
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

    @Suppress("DEPRECATION")
    private fun setWifi(on: Boolean): ExecutionResult {
        val ok = wifiManager.setWifiEnabled(on)
        return ExecutionResult(ok, if (ok) "WiFi 已${if (on) "开启" else "关闭"}" else "WiFi 操作失败")
    }
}
