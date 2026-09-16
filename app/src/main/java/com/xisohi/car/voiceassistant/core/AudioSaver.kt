package com.xisohi.car.voiceassistant.core

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 录音保存工具类
 *
 * 每次识别完成后，把录音保存为 WAV 文件，方便用户回听判断录音质量。
 * 只保留最近 10 条，超过自动删除最旧的。
 *
 * 保存位置：/sdcard/Android/data/com.xisohi.car.voiceassistant/files/recordings/
 * 文件名格式：2026-09-16_14-30-25_导航到蚌埠站.wav
 *
 * 注意：保存的是给百度识别用的音频（2.5x 轻量增益，未经过 Vosk 的 8x 增益），
 * 更接近真实人声，适合判断录音质量。
 */
object AudioSaver {
    private const val AUDIO_DIR = "recordings"
    private const val MAX_RECORDINGS = 10
    private const val SAMPLE_RATE = 16000
    private const val CHANNELS = 1
    private const val BITS_PER_SAMPLE = 16

    private var audioDir: File? = null
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.getDefault())

    /**
     * 初始化，在 Application.onCreate() 或 MainActivity.onCreate() 中调用
     */
    fun init(ctx: Context) {
        audioDir = File(ctx.getExternalFilesDir(null), AUDIO_DIR)
        if (!audioDir!!.exists()) {
            audioDir!!.mkdirs()
        }
        LogUtils.d("AudioSaver", "录音保存目录: ${audioDir!!.absolutePath}")
    }

    /**
     * 保存录音为 WAV 文件
     *
     * @param pcmData PCM 音频数据（16bit 小端，单声道，16kHz）
     * @param label 标签（如识别结果），会附加到文件名中，方便辨认
     * @return 保存的文件路径，失败返回 null
     */
    fun saveRecording(pcmData: ByteArray, label: String = ""): String? {
        if (audioDir == null || pcmData.isEmpty()) return null

        return try {
            // 生成文件名：时间戳 + 标签
            val timestamp = dateFormat.format(Date())
            val safeLabel = sanitizeFileName(label).take(20)  // 标签最多20字符
            val fileName = if (safeLabel.isNotEmpty()) {
                "${timestamp}_${safeLabel}.wav"
            } else {
                "${timestamp}.wav"
            }

            val file = File(audioDir, fileName)
            val wavData = pcmToWav(pcmData)
            FileOutputStream(file).use { it.write(wavData) }

            LogUtils.d("AudioSaver", "录音已保存: ${file.name}, 大小: ${file.length()} bytes, 时长: ${pcmData.size / 32000.0f}s")

            // 清理旧录音，只保留最近 MAX_RECORDINGS 条
            cleanOldRecordings()

            file.absolutePath
        } catch (e: IOException) {
            LogUtils.e("AudioSaver", "保存录音失败: ${e.message}", e)
            null
        }
    }

    /**
     * 获取所有录音文件列表（按时间倒序，最新的在前）
     */
    fun getRecordings(): List<File> {
        if (audioDir == null || !audioDir!!.exists()) return emptyList()
        return audioDir!!.listFiles { _, name -> name.endsWith(".wav") }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()
    }

    /**
     * 获取录音目录路径
     */
    fun getAudioDir(): File? = audioDir

    /**
     * 清空所有录音
     */
    fun clearAll() {
        if (audioDir != null && audioDir!!.exists()) {
            audioDir!!.listFiles()?.forEach { it.delete() }
            LogUtils.d("AudioSaver", "已清空所有录音")
        }
    }

    // ==================== 内部方法 ====================

    /**
     * PCM 转 WAV（添加 WAV 文件头）
     *
     * WAV 格式：RIFF 头 + fmt 块 + data 块 + PCM 数据
     */
    private fun pcmToWav(pcmData: ByteArray): ByteArray {
        val totalDataLen = pcmData.size + 36  // 整个文件大小 - 8
        val byteRate = SAMPLE_RATE * CHANNELS * BITS_PER_SAMPLE / 8
        val blockAlign = CHANNELS * BITS_PER_SAMPLE / 8

        val header = ByteArray(44)
        // RIFF 头
        header[0] = 'R'.code.toByte()
        header[1] = 'I'.code.toByte()
        header[2] = 'F'.code.toByte()
        header[3] = 'F'.code.toByte()
        // 文件大小（小端）
        header[4] = (totalDataLen and 0xff).toByte()
        header[5] = ((totalDataLen shr 8) and 0xff).toByte()
        header[6] = ((totalDataLen shr 16) and 0xff).toByte()
        header[7] = ((totalDataLen shr 24) and 0xff).toByte()
        // WAVE 标识
        header[8] = 'W'.code.toByte()
        header[9] = 'A'.code.toByte()
        header[10] = 'V'.code.toByte()
        header[11] = 'E'.code.toByte()
        // fmt 块标识
        header[12] = 'f'.code.toByte()
        header[13] = 'm'.code.toByte()
        header[14] = 't'.code.toByte()
        header[15] = ' '.code.toByte()
        // fmt 块大小（16 = PCM）
        header[16] = 16
        header[17] = 0
        header[18] = 0
        header[19] = 0
        // 音频格式（1 = PCM）
        header[20] = 1
        header[21] = 0
        // 声道数
        header[22] = CHANNELS.toByte()
        header[23] = 0
        // 采样率
        header[24] = (SAMPLE_RATE and 0xff).toByte()
        header[25] = ((SAMPLE_RATE shr 8) and 0xff).toByte()
        header[26] = ((SAMPLE_RATE shr 16) and 0xff).toByte()
        header[27] = ((SAMPLE_RATE shr 24) and 0xff).toByte()
        // 字节率
        header[28] = (byteRate and 0xff).toByte()
        header[29] = ((byteRate shr 8) and 0xff).toByte()
        header[30] = ((byteRate shr 16) and 0xff).toByte()
        header[31] = ((byteRate shr 24) and 0xff).toByte()
        // 块对齐
        header[32] = blockAlign.toByte()
        header[33] = 0
        // 位深度
        header[34] = BITS_PER_SAMPLE.toByte()
        header[35] = 0
        // data 块标识
        header[36] = 'd'.code.toByte()
        header[37] = 'a'.code.toByte()
        header[38] = 't'.code.toByte()
        header[39] = 'a'.code.toByte()
        // data 块大小
        header[40] = (pcmData.size and 0xff).toByte()
        header[41] = ((pcmData.size shr 8) and 0xff).toByte()
        header[42] = ((pcmData.size shr 16) and 0xff).toByte()
        header[43] = ((pcmData.size shr 24) and 0xff).toByte()

        return header + pcmData
    }

    /**
     * 清理旧录音，只保留最近 MAX_RECORDINGS 条
     */
    private fun cleanOldRecordings() {
        val recordings = getRecordings()
        if (recordings.size > MAX_RECORDINGS) {
            recordings.drop(MAX_RECORDINGS).forEach { file ->
                file.delete()
                LogUtils.d("AudioSaver", "删除旧录音: ${file.name}")
            }
        }
    }

    /**
     * 清理文件名中的非法字符
     */
    private fun sanitizeFileName(name: String): String {
        return name.replace(Regex("[\\\\/:*?\"<>|\\s]"), "_")
    }
}
