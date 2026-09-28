package com.xisohi.car.voiceassistant.download

import android.content.Context
import java.io.File

/**
 * 模型文件管理：目录约定 + 就绪状态判断。
 *
 * 模型为 sherpa-onnx 离线识别模型（ONNX 格式），支持两种模型包结构：
 * 1. 自定义结构：models/asr/model/（sherpa-onnx 模型根）
 * 2. 直接结构：models/<模型目录>/（sherpa-onnx 模型根）
 *
 * sherpa-onnx 模型根目录判定：目录内包含 tokens.txt，且至少包含一个 ONNX 模型文件
 * （encoder*.onnx / decoder*.onnx / joiner*.onnx 三文件 transducer 模型，或 model*.onnx 单文件 CTC 模型）。
 *
 * 支持两个存储位置：
 * 1. 内部存储：filesDir/va/models/（APP 内下载的模型）
 * 2. 外部存储：getExternalFilesDir(null)/va/models/（用户手动放置的模型，方便调试）
 *
 * 目录结构：
 *   filesDir/va/
 *     models/            模型包解压根目录
 *       asr/model/      自定义结构：sherpa-onnx 模型根
 *       <模型目录>/     直接结构：sherpa-onnx 模型根
 *     packages/          下载的模型包 zip 缓存
 *     config/            意图规则等可热更新配置
 */
object ModelManager {

    const val ROOT_DIR = "va"
    const val MODELS_DIR = "models"
    const val PACKAGES_DIR = "packages"
    const val CONFIG_DIR = "config"
    const val PACK_FILE_NAME = "models.zip"

    fun rootDir(context: Context): File =
        File(context.filesDir, ROOT_DIR).apply { mkdirs() }

    fun modelsDir(context: Context): File =
        File(rootDir(context), MODELS_DIR).apply { mkdirs() }

    /**
     * 外部存储的模型目录（用户手动放置模型时用，方便调试）
     * 路径：/sdcard/Android/data/<package>/files/va/models/
     */
    fun externalModelsDir(context: Context): File? {
        return try {
            val externalFilesDir = context.getExternalFilesDir(null) ?: return null
            File(externalFilesDir, "$ROOT_DIR/$MODELS_DIR").apply { mkdirs() }
        } catch (_: Exception) {
            null
        }
    }

    fun packagesDir(context: Context): File =
        File(rootDir(context), PACKAGES_DIR).apply { mkdirs() }

    fun configDir(context: Context): File =
        File(rootDir(context), CONFIG_DIR).apply { mkdirs() }

    fun packFile(context: Context): File =
        File(packagesDir(context), PACK_FILE_NAME)

    /**
     * 模型是否已就绪：找到 sherpa-onnx 模型根目录（含 tokens.txt + onnx 模型文件）。
     * 支持两种结构：自定义 asr/model/ 和直接 models/ 下的模型目录
     * 支持两个位置：内部存储和外部存储
     */
    fun isModelReady(context: Context): Boolean {
        return findAsrModelDir(context) != null
    }

    /**
     * 定位 sherpa-onnx 模型根目录（含 tokens.txt + onnx 模型文件）。找不到返回 null。
     *
     * 搜索顺序：
     * 1. 内部存储 - 自定义结构：models/asr/model/
     * 2. 内部存储 - 自定义结构兜底：models/asr/ 下第一个合法模型目录
     * 3. 内部存储 - 直接结构：models/ 下第一个合法模型目录
     * 4. 外部存储 - 自定义结构：models/asr/model/
     * 5. 外部存储 - 直接结构：models/ 下第一个合法模型目录
     */
    fun findAsrModelDir(context: Context): File? {
        // 先从内部存储找
        findInModelsDir(modelsDir(context))?.let { return it }

        // 再从外部存储找（用户手动放置的模型）
        externalModelsDir(context)?.let { externalDir ->
            findInModelsDir(externalDir)?.let { return it }
        }

        return null
    }

    /**
     * 在指定的 models 目录中查找 sherpa-onnx 模型根目录
     */
    private fun findInModelsDir(modelsDir: File): File? {
        if (!modelsDir.isDirectory) return null

        // 1. 自定义结构：models/asr/model/
        val asr = File(modelsDir, "asr")
        if (asr.isDirectory) {
            val nested = File(asr, "model")
            if (nested.isDirectory && isSherpaModelDir(nested)) return nested
            // 2. 自定义结构兜底：asr/ 下第一个合法模型目录
            asr.listFiles()?.forEach { f ->
                if (f.isDirectory && isSherpaModelDir(f)) return f
            }
        }

        // 3. 直接结构：models/ 下第一个合法模型目录
        modelsDir.listFiles()?.forEach { f ->
            if (f.isDirectory && f.name != "asr" && isSherpaModelDir(f)) return f
        }

        return null
    }

    /**
     * 判断是否为 sherpa-onnx 模型根目录：
     * 必须包含 tokens.txt，且包含至少一个 ONNX 模型文件
     * （transducer 三文件 encoder/decoder/joiner，或单文件 model.onnx CTC/paraformer）
     */
    fun isSherpaModelDir(dir: File): Boolean {
        if (!dir.isDirectory) return false
        if (!File(dir, "tokens.txt").exists()) return false
        val files = dir.listFiles()?.map { it.name } ?: return false
        val hasOnnx = files.any { name ->
            name.endsWith(".onnx") &&
                    (name.startsWith("encoder") || name.startsWith("decoder") ||
                            name.startsWith("joiner") || name.startsWith("model"))
        }
        return hasOnnx
    }
}
