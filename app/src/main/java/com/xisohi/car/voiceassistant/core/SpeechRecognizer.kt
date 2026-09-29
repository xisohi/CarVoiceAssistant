package com.xisohi.car.voiceassistant.core

import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import com.k2fsa.sherpa.onnx.OnlineZipformer2CtcModelConfig
import java.io.File
import kotlin.math.sqrt

/**
 * 语音识别封装（sherpa-onnx，完全离线，基于 ONNX Runtime）。
 *
 * 替代原 Vosk 方案，引擎从 Vosk（Kaldi）切换为 sherpa-onnx（Next-gen Kaldi + ONNX Runtime）：
 * 1. 流式 Zipformer 中文模型，体积更小（约 31MB）、识别更快、内存占用更低，32 位老车机友好；
 * 2. 模型文件为 ONNX 格式（encoder/decoder/joiner + tokens.txt，或单模型 model.onnx + tokens.txt）；
 * 3. 不依赖 sherpa-onnx 内置端点检测（enableEndpoint=false），端点仍由外部基于音频能量（RMS）自主判断，
 *    与原有 VAD 逻辑完全一致；
 * 4. OnlineRecognizer 预加载缓存：服务启动时调用 preload() 加载模型到内存，
 *    后续 create() 直接复用缓存的 OnlineRecognizer，只创建 OnlineStream（毫秒级），
 *    避免车机上每次识别都要等模型加载。
 *
 * 用法：
 * 1. 服务启动时调用 SpeechRecognizer.preload(modelDir) 预加载
 * 2. 循环调用 feed() 获取 partial 识别结果
 * 3. 外部自己计算 RMS 判断是否静音，连续静音超阈值则结束
 * 4. 调用 finish() 获取最终识别文本
 */
class SpeechRecognizer private constructor(
    private val recognizer: OnlineRecognizer,
    private val stream: OnlineStream,
    private var floatBuf: FloatArray
) {

    companion object {
        const val SAMPLE_RATE = 16000f
        const val SAMPLE_RATE_INT = 16000

        // OnlineRecognizer 缓存（预加载后复用，避免每次识别都重新加载模型）
        @Volatile
        private var cachedRecognizer: OnlineRecognizer? = null
        @Volatile
        private var cachedModelDir: String? = null

        /**
         * 预加载 sherpa-onnx 模型到内存（服务启动时调用，非阻塞后台执行）
         * 后续 create() 会直接复用缓存的 OnlineRecognizer，只创建 OnlineStream
         */
        fun preload(modelDir: File) {
            val dirPath = modelDir.absolutePath
            // 如果已经缓存了同一个模型，直接返回
            if (cachedRecognizer != null && cachedModelDir == dirPath) {
                return
            }
            synchronized(this) {
                // 双重检查
                if (cachedRecognizer != null && cachedModelDir == dirPath) {
                    return
                }
                // 释放旧的缓存识别器
                try { cachedRecognizer?.release() } catch (_: Exception) {}
                // 加载新模型并缓存
                cachedRecognizer = buildRecognizer(modelDir, null)
                cachedModelDir = dirPath
            }
        }

        /**
         * 创建语音识别器
         * 如果模型已预加载缓存，直接复用 OnlineRecognizer，只创建 OnlineStream（毫秒级）
         * 否则创建新的 OnlineRecognizer（耗时，车机上可能 1-2 秒）
         *
         * ★ 审计 #1 修复：整个方法加 @Synchronized，缓存读/写/替换在同一把锁内完成。
         * 修复前：缓存读（cachedRecognizer/cachedModelDir）在锁外判断，且"路径不同"时
         * 锁内 release 旧缓存——若另一线程正用旧识别器跑活动流，release 会使其 stream 失效崩溃。
         * 修复后：create 全流程串行，同一时刻只有一个识别器被创建/替换；
         * 上层（VoiceAssistantService.offlineRecognitionActive）也保证同一时刻只有一个识别会话，双保险。
         *
         * 注意：缓存不区分 grammar——复用缓存时 decodingMethod 沿用首次 buildRecognizer 的值。
         * 项目当前始终以 create(modelDir) 无 grammar 调用（greedy_search 自由听写），无实际影响；
         * 若未来引入热词模式，需在缓存命中时校验 decodingMethod 或按 grammar 重建。
         *
         * @param grammar 可选热词短语（对应原 Vosk grammar 词表）。
         *        传入非空列表时启用 modified_beam_search 解码 + 热词提升，提高指令命中率；
         *        为空时使用 greedy_search 自由听写（默认，项目当前使用方式）。
         */
        @Synchronized
        fun create(modelDir: File, grammar: List<String>? = null): SpeechRecognizer {
            val dirPath = modelDir.absolutePath
            val recognizer: OnlineRecognizer

            // 尝试使用缓存的识别器
            val cached = cachedRecognizer
            if (cached != null && cachedModelDir == dirPath) {
                recognizer = cached
            } else {
                // 没有缓存，创建新识别器（同时缓存起来供后续使用）
                recognizer = buildRecognizer(modelDir, grammar)
                if (cachedRecognizer == null || cachedModelDir != dirPath) {
                    try { cachedRecognizer?.release() } catch (_: Exception) {}
                    cachedRecognizer = recognizer
                    cachedModelDir = dirPath
                }
            }

            val hotwords = grammar?.takeIf { it.isNotEmpty() }?.joinToString(" ") ?: ""
            val stream = try {
                recognizer.createStream(hotwords)
            } catch (e: Exception) {
                recognizer.createStream("")
            }
            return SpeechRecognizer(recognizer, stream, FloatArray(1024))
        }

        /**
         * 释放缓存的识别器（Service 销毁时调用）
         * 调用后 cachedRecognizer 置空，下次 create() 会重新加载
         */
        fun releaseCachedModel() {
            synchronized(this) {
                try { cachedRecognizer?.release() } catch (_: Exception) {}
                cachedRecognizer = null
                cachedModelDir = null
            }
        }

        /**
         * 构建 sherpa-onnx 在线识别器。
         * 自动识别模型目录中的模型类型：
         * 1. 单模型 CTC：model.onnx / model.int8.onnx / model.fp16.onnx + tokens.txt（zipformer2-ctc）
         * 2. Transducer 三文件：encoder/decoder/joiner 各一个 .onnx + tokens.txt（zipformer / zipformer2）
         */
        private fun buildRecognizer(modelDir: File, grammar: List<String>?): OnlineRecognizer {
            val modelConfig = buildModelConfig(modelDir)
            val config = OnlineRecognizerConfig(
                featConfig = FeatureConfig(sampleRate = SAMPLE_RATE_INT, featureDim = 80),
                modelConfig = modelConfig,
                // 端点检测由外部基于 RMS 自主判断，关闭引擎内置端点
                enableEndpoint = false,
                // 传入热词时用 modified_beam_search 提升命中率；否则 greedy_search 自由听写
                decodingMethod = if (grammar.isNullOrEmpty()) "greedy_search" else "modified_beam_search",
                maxActivePaths = 4,
                hotwordsScore = 1.5f
            )
            return OnlineRecognizer(config = config)
        }

        /** 根据模型目录文件自动推断 OnlineModelConfig */
        private fun buildModelConfig(modelDir: File): OnlineModelConfig {
            val files = modelDir.listFiles()?.map { it.name }?.toSet() ?: emptySet()
            val tokens = File(modelDir, "tokens.txt").absolutePath

            // 1. 单模型 CTC：model.onnx / model.int8.onnx / model.fp16.onnx
            val singleModel = listOf("model.onnx", "model.int8.onnx", "model.fp16.onnx")
                .firstOrNull { File(modelDir, it).exists() }
            if (singleModel != null) {
                return OnlineModelConfig(
                    zipformer2Ctc = OnlineZipformer2CtcModelConfig(
                        model = File(modelDir, singleModel).absolutePath
                    ),
                    tokens = tokens,
                    numThreads = 2,
                    debug = false,
                    provider = "cpu",
                    modelType = "zipformer2"
                )
            }

            // 2. Transducer 三文件：encoder / decoder / joiner
            val encoder = pickModelFile(modelDir, "encoder", files)
                ?: throw IllegalArgumentException("模型目录缺少 encoder onnx 文件: ${modelDir.absolutePath}")
            val decoder = pickModelFile(modelDir, "decoder", files)
                ?: throw IllegalArgumentException("模型目录缺少 decoder onnx 文件: ${modelDir.absolutePath}")
            val joiner = pickModelFile(modelDir, "joiner", files)
                ?: throw IllegalArgumentException("模型目录缺少 joiner onnx 文件: ${modelDir.absolutePath}")

            // 模型类型：文件名为 encoder.onnx / encoder.int8.onnx（无 epoch-avg 后缀）→ zipformer2，
            // 否则（如 encoder-epoch-99-avg-1.int8.onnx）→ zipformer
            val isNewStyle = files.any { it == "encoder.onnx" || it == "encoder.int8.onnx" }
            return OnlineModelConfig(
                transducer = OnlineTransducerModelConfig(
                    encoder = File(modelDir, encoder).absolutePath,
                    decoder = File(modelDir, decoder).absolutePath,
                    joiner = File(modelDir, joiner).absolutePath
                ),
                tokens = tokens,
                numThreads = 2,
                debug = false,
                provider = "cpu",
                modelType = if (isNewStyle) "zipformer2" else "zipformer"
            )
        }

        /**
         * 按优先级挑选模型文件：
         * - encoder：encoder.int8.onnx > encoder.onnx > encoder-*.int8.onnx > encoder-*.onnx
         * - decoder：decoder.onnx > decoder.int8.onnx > decoder-*.onnx > decoder-*.int8.onnx
         * - joiner：joiner.int8.onnx > joiner.onnx > joiner-*.int8.onnx > joiner-*.onnx
         */
        private fun pickModelFile(modelDir: File, kind: String, files: Set<String>): String? {
            val preferInt8 = kind != "decoder"
            val candidates = if (preferInt8) {
                listOf(
                    "$kind.int8.onnx",
                    "$kind.onnx",
                    "$kind-epoch-99-avg-1.int8.onnx",
                    "$kind-epoch-99-avg-1.onnx"
                )
            } else {
                listOf(
                    "$kind.onnx",
                    "$kind.int8.onnx",
                    "$kind-epoch-99-avg-1.onnx",
                    "$kind-epoch-99-avg-1.int8.onnx"
                )
            }
            candidates.firstOrNull { File(modelDir, it).exists() }?.let { return it }
            // 兜底：任意 $kind*.onnx
            return files.firstOrNull { it.startsWith("$kind") && it.endsWith(".onnx") }
        }

        /**
         * 计算 16-bit PCM 音频的 RMS（均方根）能量值
         * 用于判断是否静音：值越小越安静
         */
        fun calculateRms(audioData: ShortArray, length: Int): Float {
            if (length <= 0) return 0f
            var sum = 0.0
            for (i in 0 until length) {
                val sample = audioData[i].toInt()
                sum += (sample * sample).toDouble()
            }
            val rms = sqrt(sum / length)
            return rms.toFloat()
        }
    }

    /**
     * 喂入 16kHz 单声道 PCM16 数据；返回部分识别文本（可能为 null）
     * 注意：不依赖 sherpa-onnx 的端点检测，端点由外部基于 RMS 自主判断
     */
    fun feed(data: ByteArray, len: Int): String? {
        val sampleCount = len / 2
        if (sampleCount <= 0) return null
        // 复用 float 缓冲，避免频繁 GC；
        // 注意必须与 sampleCount 严格等长：sherpa-onnx 的 acceptWaveform 按数组全长读取，
        // 若传入更大数组会把上一帧残留数据也喂给引擎
        if (floatBuf.size != sampleCount) {
            floatBuf = FloatArray(sampleCount)
        }
        // short → float（-32768..32767 → -1.0..1.0）
        var j = 0
        var i = 0
        while (i < len) {
            val sample = ((data[i].toInt() and 0xFF) or (data[i + 1].toInt() shl 8)).toShort()
            floatBuf[j] = sample / 32768.0f
            i += 2
            j++
        }
        try {
            stream.acceptWaveform(floatBuf, SAMPLE_RATE_INT)
            // ⚠ 必须用 isReady() 门控后再 decode：
            // sherpa-onnx 流式模型每次解码需一个 chunk（如 39 帧 ≈ 390ms），
            // 若音频不足就 decode，C++ 层 features.cc:GetFrames 会打印
            // "%d + %d > %d" 并调用 SHERPA_ONNX_EXIT(-1) → abort 整个进程（闪退）。
            // isReady() 返回 false 表示帧数不足，此时应等待更多音频而非解码。
            // guard 只是防死循环保险，实际次数由 isReady() 门控决定；
            // 与 finish() 统一上限，避免长音频一次喂入大量 chunk 时提前截断
            var guard = 0
            while (recognizer.isReady(stream) && guard < 20) {
                recognizer.decode(stream)
                guard++
            }
            val text = recognizer.getResult(stream).text
            return text.ifEmpty { null }
        } catch (_: Exception) {
            return null
        }
    }

    /**
     * 结束识别并返回最终文本
     */
    fun finish(): String {
        return try {
            stream.inputFinished()
            // 继续解码直到引擎输出全部结果
            var guard = 0
            while (recognizer.isReady(stream) && guard < 20) {
                recognizer.decode(stream)
                guard++
            }
            recognizer.getResult(stream).text
        } catch (_: Exception) {
            ""
        }
    }

    fun release() {
        try { stream.release() } catch (_: Exception) {}
        // 注意：不要关闭 OnlineRecognizer！
        // OnlineRecognizer 的生命周期由缓存管理（preload/create 时缓存，releaseCachedModel 时释放）
        // 这里只释放本次识别的 OnlineStream，避免影响后续识别复用缓存的识别器
    }
}
