package com.xisohi.car.voiceassistant.download

import android.content.Context
import com.xisohi.car.voiceassistant.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

data class DownloadProgress(val bytesDownloaded: Long, val totalBytes: Long) {
    val fraction: Float
        get() = if (totalBytes <= 0) 0f else bytesDownloaded.toFloat() / totalBytes
}

class ModelDownloadException(message: String, cause: Throwable? = null) :
    Exception(message, cause)

/**
 * 模型包下载：HTTP Range 断点续传 + MD5 校验。
 * 下载产物写入 packages/models.zip，由 [ModelInstaller] 负责解压。
 */
class ModelDownloader(private val context: Context) {

    // ★ callTimeout 兜底：readTimeout 只管"单次读"，服务端滴流发送（每 59 秒吐几个字节）
    // 能让下载无限期挂起。callTimeout 覆盖整个调用（含读 body），30 分钟是按 31MB 模型包
    // 在慢车机上 ~17KB/s 估算的上限。
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.MINUTES)
        .build()

    /** 同一时刻只允许一个下载：并发的两次调用会共写同一个 .part，交叉损坏 */
    private val downloadMutex = Mutex()

    suspend fun download(
        url: String = BuildConfig.MODEL_PACK_URL,
        onProgress: (DownloadProgress) -> Unit
    ): File {
        // ★ 串行化后再进入原逻辑（实现见 downloadLocked）：并发下载会同时往 .part 追加。
        return downloadMutex.withLock { downloadLocked(url, onProgress) }
    }

    private suspend fun downloadLocked(
        url: String,
        onProgress: (DownloadProgress) -> Unit
    ): File = withContext(Dispatchers.IO) {
        val target = ModelManager.packFile(context)
        val tmp = File(target.parentFile, target.name + ".part")

        var existing = 0L
        if (tmp.exists()) existing = tmp.length()

        val request = Request.Builder()
            .url(url)
            .header("Range", "bytes=$existing-")
            .build()

        client.newCall(request).execute().use { resp ->
            if (resp.code == 416) {
                // ★ 416 = Range 越界：服务端换了包 / 新包更小 / .part 是上一次的残留。
                // 原实现直接抛异常且不清理 .part —— 之后每次重试都会算出同样的 Range，
                // 永远 416，用户只能清应用数据。这里删掉残留，让下次重试从头下载。
                android.util.Log.w("ModelDownloader", "服务器返回 416（Range 越界，疑似模型包已更换），删除残留临时文件 ${tmp.name}")
                tmp.delete()
                throw ModelDownloadException("断点续传已失效（HTTP 416），已清理临时文件，请重新下载")
            }
            if (resp.code == 200 && existing > 0) {
                // 服务器不支持 Range：从头重下
                tmp.delete()
                existing = 0L
            }
            if (resp.code != 200 && resp.code != 206) {
                throw ModelDownloadException("下载失败：HTTP ${resp.code}")
            }
            if (resp.code == 206) {
                // ★ 校验 Content-Range 的起始偏移与我们请求的断点是否一致：错位的 206
                // （镜像/代理转发错误）会把新数据接到旧 .part 后面，拼出一个坏包。
                val contentRange = resp.header("Content-Range")
                if (contentRange == null) {
                    android.util.Log.w("ModelDownloader", "206 响应缺少 Content-Range，无法校验断点偏移")
                } else {
                    val start = contentRange.substringAfter("bytes ", "")
                        .substringBefore("-").trim().toLongOrNull()
                    if (start == null || start != existing) {
                        tmp.delete()
                        throw ModelDownloadException(
                            "服务器分段起点与断点不符（Content-Range=$contentRange，期望起始 $existing），已清理临时文件，请重新下载"
                        )
                    }
                }
            }
            val body = resp.body
                ?: throw ModelDownloadException("响应体为空")
            // ★ 记下服务器声明的长度（可能缺省：chunked 传输没有 Content-Length）。
            // 只有它存在时才能校验"下完的长度对不对"，否则续传场景会把正常下载误判成断包。
            val contentLength = resp.header("Content-Length")?.toLongOrNull()
            val total = (contentLength ?: 0L) + existing
            body.byteStream().use { input ->
                FileOutputStream(tmp, true).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var done = existing
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        done += n
                        onProgress(DownloadProgress(done, total))
                    }
                }
            }
            // ★ 完整性兜底：MODEL_PACK_MD5 为空时（当前 build.gradle.kts 就是空字符串）
            // 整包不做任何内容校验，这里至少比对实际文件长度与 Content-Length，拦住
            // "连接中途断开、只下了一半" —— 原实现会把半截文件直接 rename 成 models.zip。
            val actualSize = tmp.length()
            if (contentLength != null && actualSize != total) {
                tmp.delete()
                throw ModelDownloadException("下载不完整：期望 $total 字节，实际 $actualSize 字节，已清理临时文件")
            }
        }

        val expectMd5 = BuildConfig.MODEL_PACK_MD5
        if (expectMd5.isNotBlank()) {
            val actual = md5(tmp)
            if (!actual.equals(expectMd5, ignoreCase = true)) {
                tmp.delete()
                throw ModelDownloadException("MD5 校验失败：期望 $expectMd5 实际 $actual")
            }
        } else {
            // ★ build.gradle.kts 的 MODEL_PACK_MD5 目前是空字符串 ⇒ 整包不做 MD5 校验，
            // 只能靠上面的长度比对拦截断包。生产环境请填上 zip 的 MD5
            //（见 README「模型包打包与托管」；模型 zip 解压后就进私有目录，不校验等于全盘信任下载源）。
            android.util.Log.w("ModelDownloader", "MODEL_PACK_MD5 未配置，模型包不做完整性校验（仅比对长度）")
        }

        if (tmp.renameTo(target)) target
        else {
            tmp.copyTo(target, overwrite = true)
            tmp.delete()
            target
        }
    }

    private fun md5(file: File): String {
        val digest = MessageDigest.getInstance("MD5")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
