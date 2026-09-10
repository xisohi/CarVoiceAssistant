package com.xisohi.car.voiceassistant.core

import android.media.AudioRecord
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.util.Log

/**
 * 音频降噪处理器
 * 包含三种降噪机制：
 * 1. Android 系统自带降噪（NoiseSuppressor + AutomaticGainControl）
 * 2. 软件高通滤波器（去除低频发动机噪音）
 * 3. RNNoise 深度学习降噪（预留接口，需集成 native 库）
 */
class AudioNoiseReducer private constructor(
    private val audioSessionId: Int
) {
    companion object {
        private const val TAG = "AudioNoiseReducer"

        /**
         * 创建并启用音频降噪
         * @param audioRecord 已初始化的 AudioRecord
         * @return AudioNoiseReducer 实例（如果硬件不支持某些效果，会静默跳过）
         */
        fun create(audioRecord: AudioRecord): AudioNoiseReducer {
            val reducer = AudioNoiseReducer(audioRecord.audioSessionId)
            reducer.enableSystemEffects()
            return reducer
        }
    }

    private var noiseSuppressor: NoiseSuppressor? = null
    private var automaticGainControl: AutomaticGainControl? = null
    private var isReleased = false

    // RNNoise 深度学习降噪器
    private val rnnoiseDenoiser = RnNoiseDenoiser()
    private var rnnoiseInitialized = false

    // 高通滤波器状态（一阶 IIR 滤波器，去除 100Hz 以下低频噪音）
    private var highPassPrevSample: Float = 0f
    private var highPassPrevInput: Float = 0f
    private val highPassAlpha: Float = run {
        // 采样率 16000Hz，截止频率 100Hz
        // alpha = 2π * fc / (2π * fc + fs)
        val fc = 100.0  // 截止频率 100Hz
        val fs = 16000.0  // 采样率
        val rc = 1.0 / (2.0 * Math.PI * fc)
        val dt = 1.0 / fs
        (rc / (rc + dt)).toFloat()
    }

    /**
     * 启用 Android 系统自带的音频效果
     * 包括：噪音抑制（NoiseSuppressor）和自动增益控制（AutomaticGainControl）
     * 注意：这些效果是否生效取决于硬件驱动，不支持时会静默失败
     */
    private fun enableSystemEffects() {
        try {
            if (NoiseSuppressor.isAvailable()) {
                noiseSuppressor = NoiseSuppressor.create(audioSessionId)
                noiseSuppressor?.enabled = true
                Log.d(TAG, "NoiseSuppressor 已启用")
            } else {
                Log.w(TAG, "NoiseSuppressor 不可用（硬件不支持）")
            }
        } catch (e: Exception) {
            Log.w(TAG, "启用 NoiseSuppressor 失败: ${e.message}")
        }

        try {
            if (AutomaticGainControl.isAvailable()) {
                automaticGainControl = AutomaticGainControl.create(audioSessionId)
                automaticGainControl?.enabled = true
                Log.d(TAG, "AutomaticGainControl 已启用")
            } else {
                Log.w(TAG, "AutomaticGainControl 不可用（硬件不支持）")
            }
        } catch (e: Exception) {
            Log.w(TAG, "启用 AutomaticGainControl 失败: ${e.message}")
        }
    }

    /**
     * 软件高通滤波器：去除 100Hz 以下的低频噪音（如发动机噪音、风噪低频部分）
     * 对人声（300-3000Hz）影响很小
     *
     * @param audioData 16位 PCM 音频数据（原地修改）
     * @param length 有效数据长度
     */
    fun applyHighPassFilter(audioData: ShortArray, length: Int) {
        if (isReleased) return
        val alpha = highPassAlpha
        for (i in 0 until length) {
            val input = audioData[i].toFloat()
            // 一阶高通滤波器：y[n] = alpha * (y[n-1] + x[n] - x[n-1])
            val output = alpha * (highPassPrevSample + input - highPassPrevInput)
            highPassPrevSample = output
            highPassPrevInput = input
            // 限制范围，防止溢出
            audioData[i] = output.coerceIn(-32768f, 32767f).toInt().toShort()
        }
    }

    /**
     * RNNoise 深度学习降噪
     * 基于递归神经网络的实时降噪算法，能有效过滤：
     * - 发动机噪音
     * - 路噪、风噪
     * - 空调噪音
     * - 各种非人声背景噪音
     *
     * 使用预编译的 librnnoise.so，通过 JNA 直接调用
     *
     * @param audioData 16位 PCM 音频数据（原地修改）
     * @param length 有效数据长度
     */
    fun applyRnNoise(audioData: ShortArray, length: Int) {
        if (isReleased) return
        // 懒初始化：第一次调用时初始化 RNNoise
        if (!rnnoiseInitialized) {
            rnnoiseInitialized = rnnoiseDenoiser.init()
            if (rnnoiseInitialized) {
                Log.d(TAG, "RNNoise 深度学习降噪已启用")
            } else {
                Log.w(TAG, "RNNoise 初始化失败，将跳过深度学习降噪")
                return
            }
        }
        if (rnnoiseInitialized) {
            rnnoiseDenoiser.process(audioData, length)
        }
    }

    /**
     * 应用全部降噪处理（高通滤波 + RNNoise 深度学习降噪）
     * 系统降噪在 AudioRecord 创建时已启用，不需要每帧调用
     *
     * @param audioData 16位 PCM 音频数据（原地修改）
     * @param length 有效数据长度
     * @param enableRnNoise 是否启用 RNNoise 深度学习降噪（默认开启）
     */
    fun process(audioData: ShortArray, length: Int, enableRnNoise: Boolean = true) {
        if (isReleased) return
        // 1. 高通滤波（去除100Hz以下低频发动机噪音）
        applyHighPassFilter(audioData, length)
        // 2. RNNoise 深度学习降噪（过滤各种非人声背景噪音）
        if (enableRnNoise) {
            applyRnNoise(audioData, length)
        }
    }

    /**
     * 释放资源
     */
    fun release() {
        if (isReleased) return
        isReleased = true
        try {
            noiseSuppressor?.release()
            noiseSuppressor = null
        } catch (e: Exception) {
            Log.w(TAG, "释放 NoiseSuppressor 失败: ${e.message}")
        }
        try {
            automaticGainControl?.release()
            automaticGainControl = null
        } catch (e: Exception) {
            Log.w(TAG, "释放 AutomaticGainControl 失败: ${e.message}")
        }
        try {
            rnnoiseDenoiser.release()
        } catch (e: Exception) {
            Log.w(TAG, "释放 RnNoiseDenoiser 失败: ${e.message}")
        }
        Log.d(TAG, "AudioNoiseReducer 已释放")
    }
}
