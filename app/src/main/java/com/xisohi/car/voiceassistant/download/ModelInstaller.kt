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

        // ★ 审计 #24 修复：先解压到临时目录，全部成功后再原子替换正式目录——
        // 修复前先清空 modelsDir 再解压，解压中途失败（zip 损坏/磁盘满/网络中断）会留下
        // "旧模型已删、新模型不完整"的中间态，isModelReady() 返回 false，用户必须重新下载整个模型包。
        val tmpDir = File(modelsDir.parentFile, ".model_install_tmp_${System.currentTimeMillis()}")
        try {
            tmpDir.mkdirs()
            val tmpBase = tmpDir.canonicalFile
            ZipFile(pack).use { zip ->
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    // 统一路径分隔符：兼容 Windows 打包工具产生的 '\\'（Android/Linux 只认 '/'）
                    val normalizedName = entry.name.replace('\\', '/')
                    val outFile = File(tmpDir, normalizedName).canonicalFile
                    // 防 zip-slip
                    if (!outFile.path.startsWith(tmpBase.path + File.separator)) {
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
            // 解压全部成功：清空旧目录，再移动临时目录内容（同分区 rename，失败前旧模型始终可用）
            modelsDir.listFiles()?.forEach { it.deleteRecursively() }
            modelsDir.mkdirs()
            tmpDir.listFiles()?.forEach { src ->
                val dst = File(modelsDir, src.name)
                // ★ P2-3（审计 v6 追加）：renameTo 失败（跨分区/权限）时回退 copy+delete，
                // 避免"旧模型已删、新模型未移入"的残缺态
                if (!src.renameTo(dst)) {
                    android.util.Log.w("ModelInstaller", "模型文件 rename 失败: ${src.name}，回退为 copy+delete")
                    src.copyRecursively(dst, overwrite = true)
                    src.deleteRecursively()
                }
            }
        } finally {
            // 无论成功失败都清理临时目录（成功时内容已移走，只剩空目录）
            tmpDir.deleteRecursively()
        }
    }
}
