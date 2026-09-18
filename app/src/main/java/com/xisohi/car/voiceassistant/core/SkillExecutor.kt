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
import com.xisohi.car.voiceassistant.core.skill.AppLaunchSkill
import com.xisohi.car.voiceassistant.core.skill.BluetoothWifiSkill
import com.xisohi.car.voiceassistant.core.skill.CarControlSkill
import com.xisohi.car.voiceassistant.core.skill.MediaSkill
import com.xisohi.car.voiceassistant.core.skill.NavigationSkill
import com.xisohi.car.voiceassistant.core.skill.TimeHelpSkill
import com.xisohi.car.voiceassistant.core.skill.VolumeSkill
import com.xisohi.car.voiceassistant.core.skill.WeatherSkill
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 执行结果
 * @param handled 是否成功处理
 * @param spoken 要向用户播报的文案
 * @param needsMicPause 拉起外部导航后是否需要暂停唤醒监听给导航让麦（true=导航有语音助手）
 */
data class ExecutionResult(
    val handled: Boolean,
    val spoken: String,
    val needsMicPause: Boolean = false  // 默认不需要让麦
)

/**
 * 车控执行层。
 *
 * 系统级功能（音量/音乐/WiFi/蓝牙/导航拉起）直接实现；
 * 空调、车窗等"真正的车控"需要接入车机厂商 SDK/广播——
 * 通过 [CarControlProvider] 接口扩展，默认实现为占位提示。
 */
class SkillExecutor(private val context: Context) {

    // ===== 拆分后的 Skill 实例 =====
    private val volumeSkill = VolumeSkill(context)
    private val timeHelpSkill = TimeHelpSkill()
    private val weatherSkill = WeatherSkill(context)
    private val navigationSkill = NavigationSkill(context)
    private val mediaSkill = MediaSkill(context)
    private val bluetoothWifiSkill = BluetoothWifiSkill(context)
    private val carControlSkill = CarControlSkill()
    private val appLaunchSkill = AppLaunchSkill(context, mediaSkill)

    /**
     * 车控扩展接口（兼容旧代码，转发给 CarControlSkill）
     * 新代码请使用 CarControlSkill.CarControlProvider
     */
    var carControlProvider: CarControlSkill.CarControlProvider?
        get() = carControlSkill.provider
        set(value) { carControlSkill.provider = value }

    fun execute(intent: VoiceIntent): ExecutionResult = try {
        when (intent.action) {
            // 音量（转发给 VolumeSkill）
            "volume.set", "volume.up", "volume.down", "volume.mute" ->
                volumeSkill.execute(intent)
            // 媒体（转发给 MediaSkill）
            "media.play", "media.pause", "media.next", "media.prev" ->
                mediaSkill.execute(intent)
            // 导航（转发给 NavigationSkill）
            "nav.to", "nav.home", "nav.company", "nav.nearby" ->
                navigationSkill.execute(intent)
            // 车控（转发给 CarControlSkill）
            "climate.on", "climate.off", "climate.temp", "window.open", "window.close" ->
                carControlSkill.execute(intent)
            // 蓝牙/WiFi（转发给 BluetoothWifiSkill）
            "bt.on", "bt.off", "wifi.on", "wifi.off" ->
                bluetoothWifiSkill.execute(intent)
            // 时间/帮助（转发给 TimeHelpSkill）
            "ask.time", "ask.help" -> timeHelpSkill.execute(intent)
            // 天气（转发给 WeatherSkill）
            "ask.weather" -> weatherSkill.execute(intent)
            // 打开应用（转发给 AppLaunchSkill）
            "app.open" -> appLaunchSkill.execute(intent)
            else -> ExecutionResult(false, "这个指令我还不支持")
        }
    } catch (e: Exception) {
        ExecutionResult(false, "执行失败：${e.message ?: "未知错误"}")
    }

    // ===== 兼容旧接口（转发给对应 Skill） =====
    fun queryWeather(city: String? = null, timeIndex: Int = 0): String? =
        weatherSkill.queryWeather(city, timeIndex)

    fun getTimeIndex(text: String?): Int = weatherSkill.getTimeIndex(text)
    fun isOnlyTimeWord(word: String?): Boolean = weatherSkill.isOnlyTimeWord(word)
    fun extractCity(word: String?): String? = weatherSkill.extractCity(word)

    fun setActiveMusicPlayer(packageName: String) =
        mediaSkill.setActivePlayer(packageName)  // 只设置活跃，不自动播放

    fun selectSong(indexStr: String): ExecutionResult =
        mediaSkill.selectSong(indexStr)

}
