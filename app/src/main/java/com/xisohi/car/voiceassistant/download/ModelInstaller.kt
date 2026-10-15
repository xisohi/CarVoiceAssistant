package com.xisohi.car.voiceassistant.download

import android.content.Context
import android.os.StatFs
import java.io.File
import java.util.zip.ZipFile

/**
 * 模型包安装：解压 zip 到 models/ 目录
 *（zip-slip 路径穿越防护 + 解压体积上限 + 剩余空间检查 + 目录级原子替换）。
 *
 * 模型包目录约定（刻意不变，ModelManager 仍按原路径找模型）：
 *   models.zip
 *   └── asr/model/          sherpa-onnx 中文模型根（encoder/decoder/joiner/tokens.txt 或 model.onnx/tokens.txt）
 */
object ModelInstaller {

    private const val TAG = "ModelInstaller"

    /**
     * 解压后累计大小上限：1GB。
     * 依据：中文 zipformer 模型包压缩后约 31MB、解压后 300MB 上下（见 ModelDownloader 里
     * "31MB 模型包"的估算），1GB 是 3 倍余量；而车机 /data 分区普遍只有几个 GB 可用，
     * 没有上限时一个损坏/恶意 zip（高压缩比，几 MB 能解出几十 GB）就能把 /data 写满，
     * 拖垮整个车机（日志、其他 App 一起挂掉）。
     */
    private const val MAX_UNCOMPRESSED_BYTES = 1024L * 1024 * 1024

    /** 解压前要求的最小剩余空间余量：文件系统元数据 + 解压期间系统其他写入的缓冲 */
    private const val FREE_SPACE_MARGIN_BYTES = 32L * 1024 * 1024

    /** 临时解压目录名前缀（install() 生成目录名时也用它，别改一处漏一处） */
    private const val TMP_DIR_PREFIX = ".model_install_tmp_"

    /** 判定"遗留临时目录"的静默时长：正常解压再慢也不会超过 1 小时，
     *  用它避免误删正在进行的另一次安装 */
    private const val STALE_TMP_MS = 60 * 60 * 1000L

    fun install(context: Context, pack: File) {
        val modelsDir = ModelManager.modelsDir(context)
        // 临时目录/备份目录都放在 modelsDir 的同一个父目录（va/）下：
        // 同一个分区才能用 rename 做 O(1) 的目录级替换（renameTo 跨分区一定失败）。
        val parentDir = modelsDir.parentFile
            ?: throw IllegalStateException("模型目录没有父目录: ${modelsDir.absolutePath}")

        val tmpDir = File(parentDir, "$TMP_DIR_PREFIX${System.currentTimeMillis()}")
        // 备份目录刻意放在 va/ 下、而不是 va/models/ 里：
        // ModelManager.findInModelsDir() 只扫描 modelsDir 内部，兄弟目录 models.old
        // 不会被误当成"合法模型根"（否则清理期间会命中旧模型，装完还是旧模型）。
        val backupDir = File(parentDir, "models.old")

        // 进程在解压途中被杀会留下几百 MB 的临时目录（finally 不会执行），
        // 不清理的话下面新加的空间检查会永久报"空间不足"，用户只能清应用数据才能恢复。
        cleanupStaleTmpDirs(parentDir, tmpDir)

        try {
            if (!tmpDir.mkdirs()) {
                throw IllegalStateException("无法创建临时目录: ${tmpDir.absolutePath}")
            }
            val tmpBase = tmpDir.canonicalFile

            // ---- 1) 先按 zip 中央目录统计"解压后大小"：超上限直接失败，一个字节都不落盘 ----
            var declaredBytes = 0L
            ZipFile(pack).use { zip ->
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    if (entry.isDirectory) continue
                    // size == -1 表示 zip 没记录长度（流式写入的包），按 0 计，
                    // 靠下面"实际写出字节数"的硬上限兜底
                    if (entry.size > 0) declaredBytes += entry.size
                    if (declaredBytes > MAX_UNCOMPRESSED_BYTES) throw oversizeError()
                }
            }

            // ---- 2) 解压前检查剩余空间（modelsDir 所在分区，即 filesDir/va 所在分区）----
            // StatFs(String) 自 API 18 起标记 deprecated 但仍可用（带 flags 的构造要 API 21+），
            // 为兼容 minSdk 24 沿用旧构造。
            val availableBytes = StatFs(parentDir.absolutePath).availableBytes
            val neededBytes = declaredBytes + FREE_SPACE_MARGIN_BYTES
            if (availableBytes < neededBytes) {
                throw IllegalStateException(
                    "存储空间不足：解压需要约 ${neededBytes / 1024 / 1024}MB，" +
                            "当前可用 ${availableBytes / 1024 / 1024}MB，请清理空间后重试"
                )
            }

            // ---- 3) 解压到临时目录（zip-slip 防护 + 实际字节数上限）----
            var writtenBytes = 0L
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
                            outFile.outputStream().use { output ->
                                val buffer = ByteArray(8 * 1024)
                                while (true) {
                                    val n = input.read(buffer)
                                    if (n < 0) break
                                    writtenBytes += n
                                    // entry.size 是 zip 自己声明的（可能为 -1、也可能与实际不符），
                                    // 这里按真实写出的字节数再卡一次，保证磁盘占用不会失控
                                    if (writtenBytes > MAX_UNCOMPRESSED_BYTES) throw oversizeError()
                                    output.write(buffer, 0, n)
                                }
                            }
                        }
                    }
                }
            }

            // ---- 4) 校验临时目录内容：宁可保留旧模型，也不要把坏包换进正式目录 ----
            // 判定规则与 ModelManager.findInModelsDir() 保持一致 ⇒
            // "这里能通过" 等价于 "换名后 ModelManager.isModelReady() 为 true"。
            if (findSherpaModelDir(tmpDir) == null) {
                throw IllegalStateException("模型包内容不完整：没找到 tokens.txt + onnx 模型文件，已中止安装")
            }

            // ---- 5) 目录级原子替换：正式目录 → .old，临时目录 → 正式目录，失败回滚 ----
            // ★ 审计 #24 修复（原实现只做到"先解压再替换"，替换本身不是原子的）：
            // 原来是先 deleteRecursively() 清空正式目录、再逐个 rename 临时目录里的条目——
            // 这个窗口里进程被杀 / 某个 rename 失败，就留下"旧模型已删、新模型不全"的中间态；
            // 而且逐个搬的过程中 isSherpaModelDir()（tokens.txt 或 onnx 还没到位）会返回 false，
            // 用户看到"模型未就绪"，只能重下整个包。
            // 同一个父目录下的目录换名在 Linux 上是原子的（rename(2)），且每一步失败都能改名回滚。
            // 注意：判断"有没有旧模型"不能用 modelsDir.exists()——ModelManager.modelsDir()
            // 内部就 mkdirs()，正式目录永远"存在"（可能只是个空壳），
            // 必须看里面有没有合法模型目录，否则恢复/备份都会误判。
            val hadUsableOldModels = findSherpaModelDir(modelsDir) != null
            if (!hadUsableOldModels && backupDir.exists()) {
                // 上次在"正式目录→.old"之后、".tmp→正式目录"之前被杀：旧模型躺在 models.old，
                // 正式目录只剩 mkdirs() 出来的空壳。这里把空壳删掉再把旧模型改回来
                //（Linux 下 rename 到非空目录会 ENOTEMPTY，所以必须先删干净），
                // 而不是把 .old 当垃圾删掉——它是旧模型最后的可用副本。
                if (modelsDir.exists() && !modelsDir.deleteRecursively()) {
                    throw IllegalStateException("无法清理残缺的模型目录: ${modelsDir.absolutePath}")
                }
                if (!backupDir.renameTo(modelsDir)) {
                    throw IllegalStateException("旧模型备份恢复失败，请手动检查: ${backupDir.absolutePath}")
                }
                android.util.Log.w(TAG, "检测到上次安装中断，已把旧模型恢复到正式目录")
            }

            // 有可用旧模型才需要"改名保留"；空壳/残缺目录直接删掉，省一次换名
            // hasBackup 记录"旧模型此刻是否已被改名为备份"：换名失败时靠它决定要不要把 .old 改回来
            val hasBackup = findSherpaModelDir(modelsDir) != null
            if (hasBackup) {
                // 上次安装异常退出可能留下残留备份，先清掉（否则下面 rename 的目标非空会失败）
                if (backupDir.exists() && !backupDir.deleteRecursively()) {
                    throw IllegalStateException("无法清理上次的旧模型备份: ${backupDir.absolutePath}")
                }
                if (!modelsDir.renameTo(backupDir)) {
                    // 旧模型还完整地待在原位，直接失败退出，什么都不动
                    throw IllegalStateException("旧模型目录重命名失败，已保留原模型: ${modelsDir.absolutePath}")
                }
            } else if (modelsDir.exists() && !modelsDir.deleteRecursively()) {
                throw IllegalStateException("无法清理残缺的模型目录: ${modelsDir.absolutePath}")
            }

            if (!tmpDir.renameTo(modelsDir)) {
                // 回滚：把旧模型改回正式目录（新模型宁可没装上，也不能两个都没有）
                if (hasBackup && !backupDir.renameTo(modelsDir)) {
                    android.util.Log.e(
                        TAG,
                        "严重：新模型换名失败且旧模型回滚失败，旧模型残留在 ${backupDir.absolutePath}"
                    )
                }
                throw IllegalStateException("模型安装失败（临时目录换名失败），已回滚到旧模型")
            }

            // 新模型已经就位，这里只是清理备份：失败不影响可用性，只记日志
            if (hasBackup && backupDir.exists() && !backupDir.deleteRecursively()) {
                android.util.Log.w(TAG, "旧模型备份清理失败，可手动删除: ${backupDir.absolutePath}")
            }
        } finally {
            // 无论成功失败都清理临时目录（成功时它已被换成正式目录，这里是空操作）
            tmpDir.deleteRecursively()
        }
    }

    /** 解压体积超限的统一错误（声明大小与实际写出两处都用它，避免两处文案漂移） */
    private fun oversizeError(): IllegalStateException =
        IllegalStateException("模型包解压后超过 ${MAX_UNCOMPRESSED_BYTES / 1024 / 1024}MB 上限，已中止安装")

    /**
     * 清理上次进程被杀遗留的临时解压目录（只删名字符合前缀、且 1 小时没被改动过的，
     * 避免误删正在进行的另一次安装）。
     * 为什么需要：一个解压到一半的临时目录就是几百 MB，而 install() 的 finally
     * 只在"方法正常返回或抛异常"时执行，进程被杀不会执行——残留会让空间检查一直失败。
     */
    private fun cleanupStaleTmpDirs(parentDir: File, currentTmpDir: File) {
        val cutoff = System.currentTimeMillis() - STALE_TMP_MS
        parentDir.listFiles()?.forEach { f ->
            if (f.isDirectory && f.name.startsWith(TMP_DIR_PREFIX) &&
                f.absolutePath != currentTmpDir.absolutePath && f.lastModified() < cutoff
            ) {
                android.util.Log.w(TAG, "清理上次遗留的临时解压目录: ${f.name}")
                if (!f.deleteRecursively()) {
                    android.util.Log.w(TAG, "遗留临时目录清理失败: ${f.absolutePath}")
                }
            }
        }
    }

    /**
     * 在解压出的目录里定位 sherpa-onnx 模型根目录。
     * 搜索顺序刻意与 ModelManager.findInModelsDir() 完全一致
     *（asr/model → asr 下第一个合法目录 → 顶层第一个合法目录），
     * 否则会出现"这里校验通过、换名后 ModelManager 却找不到模型"的假成功。
     */
    private fun findSherpaModelDir(modelsDir: File): File? {
        val asr = File(modelsDir, "asr")
        if (asr.isDirectory) {
            val nested = File(asr, "model")
            if (nested.isDirectory && ModelManager.isSherpaModelDir(nested)) return nested
            asr.listFiles()?.forEach { f ->
                if (f.isDirectory && ModelManager.isSherpaModelDir(f)) return f
            }
        }
        modelsDir.listFiles()?.forEach { f ->
            if (f.isDirectory && f.name != "asr" && ModelManager.isSherpaModelDir(f)) return f
        }
        return null
    }
}
