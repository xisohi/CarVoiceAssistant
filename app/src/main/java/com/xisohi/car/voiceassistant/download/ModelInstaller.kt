package com.xisohi.car.voiceassistant.download

import android.content.Context
import java.io.File
import java.util.zip.ZipFile

/**
 * 模型包安装：解压 zip 到 models/ 目录（含 zip-slip 路径穿越防护）。
 *
 * 模型包目录约定：
 *   models.zip
 *   └── asr/model/          sherpa-onnx 中文模型根（encoder/decoder/joiner/tokens.txt 或 model.onnx/tokens.txt）
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
                // 统一路径分隔符：兼容 Windows 打包工具产生的 '\'（Android/Linux 只认 '/'）
                val normalizedName = entry.name.replace('\\', '/')
                val outFile = File(modelsDir, normalizedName).canonicalFile
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
