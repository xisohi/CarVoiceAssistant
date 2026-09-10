package com.xisohi.car.voiceassistant.core

import android.util.Log
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer

/**
 * RNNoise 深度学习降噪封装
 * 基于 JNA 直接调用预编译的 librnnoise.so
 *
 * RNNoise 是基于递归神经网络的实时降噪算法，能有效过滤：
 * - 发动机噪音
 * - 路噪、风噪
 * - 空调噪音
 * - 各种非人声背景噪音
 *
 * 注意：RNNoise 只支持 48kHz 音频，每帧 480 样本（10ms）
 * 本封装自动处理 16kHz 音频的上采样/下采样转换
 *
 * 重采样算法优化：
 * - 上采样：线性插值（替代最近邻插值，保留高频信息）
 * - 下采样：3点平均抗混叠滤波 + 抽取（替代简单抽取，避免混叠）
 */
class RnNoiseDenoiser {

    companion object {
        private const val TAG = "RnNoiseDenoiser"

        // RNNoise 原生库接口
        private interface RNNoiseLib : Library {
            fun rnnoise_create(model: Pointer?): Pointer
            fun rnnoise_destroy(state: Pointer)
            fun rnnoise_process_frame(state: Pointer, out: FloatArray, input: FloatArray): Float
            fun rnnoise_get_size(): Int
        }

        private var library: RNNoiseLib? = null
        private var loadAttempted = false

        /**
         * 加载 RNNoise 原生库
         * @return 成功返回 true，失败返回 false
         */
        @Synchronized
        private fun ensureLibrary(): Boolean {
            if (loadAttempted) return library != null
            loadAttempted = true
            return try {
                library = Native.load("rnnoise", RNNoiseLib::class.java)
                Log.d(TAG, "RNNoise 原生库加载成功")
                true
            } catch (e: Exception) {
                Log.e(TAG, "RNNoise 原生库加载失败: ${e.message}", e)
                library = null
                false
            }
        }

        /**
         * 检查 RNNoise 是否可用
         */
        fun isAvailable(): Boolean = ensureLibrary()
    }

    private var state: Pointer? = null
    private var isInitialized = false

    // 临时缓冲区，避免频繁分配
    // RNNoise 需要 48kHz 480样本/帧
    private val input48k = FloatArray(480)
    private val output48k = FloatArray(480)
    // 16kHz 输入缓冲区（归一化后的 float）
    private val input16k = FloatArray(160)

    /**
     * 初始化降噪器
     * @return 成功返回 true
     */
    fun init(): Boolean {
        if (isInitialized) return true
        if (!ensureLibrary()) return false
        return try {
            state = library?.rnnoise_create(null)
            isInitialized = state != null
            if (isInitialized) {
                Log.d(TAG, "RNNoise 降噪器初始化成功")
            } else {
                Log.e(TAG, "RNNoise 降噪器初始化失败：state 为 null")
            }
            isInitialized
        } catch (e: Exception) {
            Log.e(TAG, "RNNoise 初始化异常: ${e.message}", e)
            false
        }
    }

    /**
     * 处理 16kHz 16-bit PCM 音频数据（原地修改）
     * 自动完成：16kHz→48kHz线性插值上采样 → RNNoise降噪 → 48kHz→16kHz抗混叠下采样
     *
     * @param audioData 16-bit PCM 音频数据
     * @param length 有效数据长度
     * @return 处理后的 VAD（语音活动检测）概率平均值，0~1
     */
    fun process(audioData: ShortArray, length: Int): Float {
        if (!isInitialized || state == null) return 0f

        var totalVad = 0f
        var frameCount = 0

        // 16kHz 下每帧 160 样本（10ms）
        val frameSize16k = 160
        var offset = 0

        while (offset + frameSize16k <= length) {
            // 1. short -> float（归一化到 -1~1）
            for (i in 0 until frameSize16k) {
                input16k[i] = audioData[offset + i] / 32768.0f
            }

            // 2. 上采样 16kHz -> 48kHz（线性插值，替代最近邻插值）
            // 线性插值能保留更多高频信息，避免音频听起来有"颗粒感"
            for (j in 0 until 480) {
                val pos = j / 3.0f
                val i = pos.toInt()
                val frac = pos - i
                if (i + 1 < frameSize16k) {
                    // 线性插值：output = input[i] * (1-frac) + input[i+1] * frac
                    input48k[j] = input16k[i] * (1.0f - frac) + input16k[i + 1] * frac
                } else {
                    // 边界处理：最后一个样本直接复制
                    input48k[j] = input16k[i]
                }
            }

            // 3. RNNoise 处理（48kHz 480样本/帧）
            val vad = try {
                library?.rnnoise_process_frame(state!!, output48k, input48k) ?: 0f
            } catch (e: Exception) {
                Log.w(TAG, "RNNoise 处理帧异常: ${e.message}")
                0f
            }
            totalVad += vad
            frameCount++

            // 4. 下采样 48kHz -> 16kHz（3点平均抗混叠滤波 + 抽取）
            // 先做抗混叠低通滤波（3点移动平均），再抽取，避免高频噪声混叠到低频
            for (i in 0 until frameSize16k) {
                // 对每3个样本取平均，作为抗混叠滤波
                val avg = (output48k[i * 3] + output48k[i * 3 + 1] + output48k[i * 3 + 2]) / 3.0f
                // float -> short（反归一化）
                val sample = (avg * 32768.0f).toInt()
                audioData[offset + i] = sample.coerceIn(-32768, 32767).toShort()
            }

            offset += frameSize16k
        }

        return if (frameCount > 0) totalVad / frameCount else 0f
    }

    /**
     * 释放资源
     */
    fun release() {
        if (!isInitialized) return
        try {
            state?.let {
                library?.rnnoise_destroy(it)
            }
            Log.d(TAG, "RNNoise 降噪器已释放")
        } catch (e: Exception) {
            Log.w(TAG, "释放 RNNoise 异常: ${e.message}")
        } finally {
            state = null
            isInitialized = false
        }
    }
}
