package com.xisohi.car.voiceassistant.download

import android.content.Context
import java.io.File

/**
 * 模型文件管理：目录约定 + 就绪状态判断。
 *
 * 目录结构（应用私有目录，无需存储权限）：
 *   filesDir/va/
 *     models/            模型包解压根目录
 *       asr/             Vosk 模型（解压后为 asr/model/，唤醒和识别共用）
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

    fun packagesDir(context: Context): File =
        File(rootDir(context), PACKAGES_DIR).apply { mkdirs() }

    fun configDir(context: Context): File =
        File(rootDir(context), CONFIG_DIR).apply { mkdirs() }

    fun packFile(context: Context): File =
        File(packagesDir(context), PACK_FILE_NAME)

    /** 模型是否已就绪：asr 模型目录存在且非空 */
    fun isModelReady(context: Context): Boolean {
        val asrDir = File(modelsDir(context), "asr")
        return asrDir.isDirectory && asrDir.listFiles()?.isNotEmpty() == true
    }

    /** 定位 Vosk 模型根目录（内含 am/ conf/ graph/）。找不到返回 null。 */
    fun findAsrModelDir(context: Context): File? {
        val asr = File(modelsDir(context), "asr")
        if (!asr.isDirectory) return null
        // 约定：模型包内 asr/model/ 为模型根
        val nested = File(asr, "model")
        if (nested.isDirectory && File(nested, "am").exists()) return nested
        // 兜底：asr 下第一个包含 am/ 的目录
        asr.listFiles()?.forEach { f ->
            if (f.isDirectory && File(f, "am").exists()) return f
        }
        return null
    }
}
