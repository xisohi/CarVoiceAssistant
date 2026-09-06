package com.xisohi.car.voiceassistant.download

import android.content.Context
import java.io.File
import java.util.zip.ZipFile

/**
 * 模型包安装：解压 zip 到 models/ 目录（含 zip-slip 路径穿越防护）。
 *
 * 模型包目录约定：
 *   models.zip
 *   ├── asr/model/          Vosk 中文模型根（am/ conf/ graph/ 等）
 *   └── kws/xxx.ppn         自定义唤醒词（可选）
 */
object ModelInstaller {

    fun install(context: Context, pack: File) {
        val modelsDir = ModelManager.modelsDir(context)
        // 清空旧模型，避免残留冲突
        modelsDir.listFiles()?.forEach { it.deleteRecursively() }

        val base = modelsDir.canonicalFile
        ZipFile(pack).use { zip ->
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                val outFile = File(modelsDir, entry.name).canonicalFile
                // 防 zip-slip
                if (!outFile.path.startsWith(base.path + File.separator)) {
                    throw IllegalStateException("非法解压路径: ${entry.name}")
                }
                if (entry.isDirectory) {
                    outFile.mkdirs()
                } else {
                    outFile.parentFile?.mkdirs()
                    zip.getInputStream(entry).use { input ->
                        outFile.outputStream().use { output -> input.copyTo(output) }
                    }
                }
            }
        }
    }
}
