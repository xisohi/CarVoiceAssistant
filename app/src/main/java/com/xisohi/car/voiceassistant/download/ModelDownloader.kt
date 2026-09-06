package com.xisohi.car.voiceassistant.download

import android.content.Context
import com.xisohi.car.voiceassistant.BuildConfig
import kotlinx.coroutines.Dispatchers
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

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    suspend fun download(
        url: String = BuildConfig.MODEL_PACK_URL,
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
            if (resp.code == 200 && existing > 0) {
                // 服务器不支持 Range：从头重下
                tmp.delete()
                existing = 0L
            }
            if (resp.code != 200 && resp.code != 206) {
                throw ModelDownloadException("下载失败：HTTP ${resp.code}")
            }
            val body = resp.body
                ?: throw ModelDownloadException("响应体为空")
            val total = (resp.header("Content-Length")?.toLongOrNull() ?: 0L) + existing
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
        }

        val expectMd5 = BuildConfig.MODEL_PACK_MD5
        if (expectMd5.isNotBlank()) {
            val actual = md5(tmp)
            if (!actual.equals(expectMd5, ignoreCase = true)) {
                tmp.delete()
                throw ModelDownloadException("MD5 校验失败：期望 $expectMd5 实际 $actual")
            }
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
