package com.xisohi.car.voiceassistant.download

import android.content.Context
import java.io.File

/**
 * 模型文件管理：目录约定 + 就绪状态判断。
 *
 * 支持两种模型包结构：
 * 1. 自定义结构：models/asr/model/（am/ conf/ graph/）
 * 2. Vosk 官网结构：models/vosk-model-cn-0.22/（am/ conf/ graph/）
 *
 * 支持两个存储位置：
 * 1. 内部存储：filesDir/va/models/（APP 内下载的模型）
 * 2. 外部存储：getExternalFilesDir(null)/va/models/（用户手动放置的模型，方便调试）
 *
 * 目录结构：
 *   filesDir/va/
 *     models/            模型包解压根目录
 *       asr/model/      自定义结构：Vosk 模型根
 *       vosk-model-cn-0.22/  Vosk 官网结构：Vosk 模型根
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
     * 模型是否已就绪：存在包含 am/ 目录的 Vosk 模型根目录。
     * 支持两种结构：自定义 asr/model/ 和 Vosk 官网 vosk-model-cn-0.22/
     * 支持两个位置：内部存储和外部存储
     */
    fun isModelReady(context: Context): Boolean {
        return findAsrModelDir(context) != null
    }

    /**
     * 定位 Vosk 模型根目录（内含 am/ conf/ graph/）。找不到返回 null。
     *
     * 搜索顺序：
     * 1. 内部存储 - 自定义结构：models/asr/model/
     * 2. 内部存储 - 自定义结构兜底：models/asr/ 下第一个包含 am/ 的目录
     * 3. 内部存储 - Vosk 官网结构：models/ 下第一个包含 am/ 的目录
     * 4. 外部存储 - 自定义结构：models/asr/model/
     * 5. 外部存储 - Vosk 官网结构：models/ 下第一个包含 am/ 的目录
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
     * 在指定的 models 目录中查找 Vosk 模型根目录
     */
    private fun findInModelsDir(modelsDir: File): File? {
        if (!modelsDir.isDirectory) return null

        // 1. 自定义结构：models/asr/model/
        val asr = File(modelsDir, "asr")
        if (asr.isDirectory) {
            val nested = File(asr, "model")
            if (nested.isDirectory && File(nested, "am").exists()) return nested
            // 2. 自定义结构兜底：asr/ 下第一个包含 am/ 的目录
            asr.listFiles()?.forEach { f ->
                if (f.isDirectory && File(f, "am").exists()) return f
            }
        }

        // 3. Vosk 官网结构：models/ 下第一个包含 am/ 的目录
        // （如 vosk-model-cn-0.22/、vosk-model-small-cn-0.22/ 等）
        modelsDir.listFiles()?.forEach { f ->
            if (f.isDirectory && f.name != "asr" && File(f, "am").exists()) return f
        }

        return null
    }
}
