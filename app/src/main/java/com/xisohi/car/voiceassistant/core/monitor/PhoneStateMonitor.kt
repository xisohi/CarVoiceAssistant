package com.xisohi.car.voiceassistant.core.monitor

import android.content.Context
import android.telephony.PhoneStateListener
import android.telephony.TelephonyManager
import android.util.Log

/**
 * 电话状态监控器
 *
 * 职责：
 * - 监听电话状态变化（响铃/通话中/空闲）
 * - 通话开始时通知回调（暂停唤醒监听，避免麦克风冲突和误唤醒）
 * - 通话结束时通知回调（恢复唤醒监听）
 *
 * 使用方式：
 * ```
 * val monitor = PhoneStateMonitor(context, object : PhoneStateMonitor.Callback {
 *     override fun onCallStarted() { stopWakeListening() }
 *     override fun onCallEnded() { startWakeListening() }
 * })
 * monitor.start()
 * // ...
 * monitor.stop()
 * ```
 */
class PhoneStateMonitor(
    private val context: Context,
    private val callback: Callback
) {
    /** 电话状态回调 */
    interface Callback {
        /** 电话开始（响铃或通话中） */
        fun onCallStarted()
        /** 电话结束（空闲） */
        fun onCallEnded()
    }

    private var telephonyManager: TelephonyManager? = null
    private var isListening = false

    private val phoneStateListener = object : PhoneStateListener() {
        override fun onCallStateChanged(state: Int, phoneNumber: String?) {
            when (state) {
                TelephonyManager.CALL_STATE_RINGING,
                TelephonyManager.CALL_STATE_OFFHOOK -> {
                    Log.d(TAG, "电话中（state=$state），通知回调暂停唤醒监听")
                    callback.onCallStarted()
                }
                TelephonyManager.CALL_STATE_IDLE -> {
                    Log.d(TAG, "电话结束，通知回调恢复唤醒监听")
                    callback.onCallEnded()
                }
            }
        }
    }

    /** 开始监听电话状态 */
    fun start() {
        if (isListening) return
        try {
            telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
            telephonyManager?.listen(phoneStateListener, PhoneStateListener.LISTEN_CALL_STATE)
            isListening = true
            Log.i(TAG, "电话状态监听已注册")
        } catch (e: Exception) {
            Log.w(TAG, "注册电话状态监听失败: ${e.message}")
        }
    }

    /** 停止监听电话状态 */
    fun stop() {
        if (!isListening) return
        try {
            telephonyManager?.listen(phoneStateListener, PhoneStateListener.LISTEN_NONE)
            Log.d(TAG, "电话状态监听已注销")
        } catch (e: Exception) {
            Log.w(TAG, "注销电话状态监听失败: ${e.message}")
        } finally {
            isListening = false
        }
    }

    companion object {
        private const val TAG = "PhoneStateMonitor"
    }
}
