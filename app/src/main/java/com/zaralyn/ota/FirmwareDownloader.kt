package com.zaralyn.ota

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

data class DownloadResult(
    val file: File,
    val md5Expected: String,
    val md5Actual: String,
    val md5Matched: Boolean
)

interface DownloadProgressListener {
    fun onStart(totalBytes: Long, fileName: String)
    fun onProgress(downloadedBytes: Long, totalBytes: Long, bytesPerSecond: Long)
    fun onVerifying()
    fun onCanceled()
}

/**
 * 固件下载器
 *
 * - 流式写入应用外部私有目录 Download/，无需存储权限
 * - 边下边算 MD5，完成后与服务器返回的 md5 比对
 * - 支持协程取消（取消时清理未完成文件）
 */
object FirmwareDownloader {

    private const val TAG = "Downloader"
    private const val PROGRESS_INTERVAL_MS = 400L

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    suspend fun download(
        context: Context,
        pkg: OtaPackage,
        listener: DownloadProgressListener
    ): DownloadResult = withContext(Dispatchers.IO) {
        val dir = File(context.getExternalFilesDir(null), "Download").apply { if (!exists()) mkdirs() }
        val fileName = guessFileName(pkg)
        val target = File(dir, fileName)
        val part = File(dir, "$fileName.part")

        AppLogger.i(TAG, "开始下载: ${pkg.url}")
        AppLogger.i(TAG, "保存路径: ${target.absolutePath}")

        val request = Request.Builder()
            .url(pkg.url)
            .header("User-Agent", "ZaralynOTA/${BuildConfig.VERSION_NAME}")
            .build()

        val digest = MessageDigest.getInstance("MD5")
        var downloaded = 0L
        var total = pkg.size
        var lastNotify = 0L
        var lastBytes = 0L
        var lastTime = System.currentTimeMillis()

        try {
            val response = try {
                client.newCall(request).execute()
            } catch (e: Exception) {
                AppLogger.caught(TAG, "建立下载连接", e)
                throw OtaException("建立下载连接失败: ${e.message}", e)
            }

            response.use { resp ->
                if (!resp.isSuccessful) {
                    AppLogger.e(TAG, "下载失败 HTTP ${resp.code}")
                    throw OtaException("下载失败: 服务器返回 HTTP ${resp.code}")
                }
                val body = resp.body ?: throw OtaException("下载失败: 响应体为空")
                val contentLength = body.contentLength()
                if (contentLength > 0) total = contentLength
                AppLogger.i(TAG, "响应 HTTP ${resp.code}, 大小=${if (total > 0) "$total 字节" else "未知"}")

                part.delete()
                FileOutputStream(part).use { output ->
                    body.byteStream().use { input ->
                        val buffer = ByteArray(64 * 1024)
                        listener.onStart(total, fileName)
                        while (true) {
                            coroutineContext.ensureActive()
                            val read = input.read(buffer)
                            if (read == -1) break
                            output.write(buffer, 0, read)
                            digest.update(buffer, 0, read)
                            downloaded += read

                            val now = System.currentTimeMillis()
                            if (now - lastNotify >= PROGRESS_INTERVAL_MS) {
                                lastNotify = now
                                val elapsed = (now - lastTime).coerceAtLeast(1L)
                                val speed = ((downloaded - lastBytes) * 1000L / elapsed)
                                lastBytes = downloaded
                                lastTime = now
                                listener.onProgress(downloaded, total, speed)
                            }
                        }
                        output.flush()
                    }
                }
            }

            listener.onVerifying()
            val actualMd5 = digest.digest().joinToString("") { "%02x".format(it) }
            val expected = pkg.md5.lowercase().trim()
            val matched = expected.isBlank() || expected == actualMd5
            if (matched) {
                AppLogger.i(TAG, "MD5 校验通过: $actualMd5")
            } else {
                AppLogger.w(TAG, "MD5 不匹配! 期望=$expected 实际=$actualMd5")
            }

            if (target.exists()) target.delete()
            if (!part.renameTo(target)) {
                // 极少数情况下 rename 失败，退化为复制
                part.copyTo(target, overwrite = true)
                part.delete()
            }

            AppLogger.i(TAG, "下载完成: ${target.absolutePath} (${downloaded} 字节, MD5=${if (matched) "OK" else "不一致"})")
            DownloadResult(target, expected, actualMd5, matched)
        } catch (e: kotlinx.coroutines.CancellationException) {
            AppLogger.w(TAG, "下载已取消，已下载 $downloaded 字节")
            runCatching { part.delete() }
            listener.onCanceled()
            throw e
        } catch (e: OtaException) {
            runCatching { part.delete() }
            throw e
        } catch (e: Exception) {
            runCatching { part.delete() }
            AppLogger.caught(TAG, "下载固件", e)
            throw OtaException("下载失败: ${e.message}", e)
        }
    }

    /** 依据 URL / 版本号推断文件名 */
    private fun guessFileName(pkg: OtaPackage): String {
        val fromUrl = pkg.url.substringBefore('?').substringAfterLast('/').trim()
        if (fromUrl.isNotBlank() && fromUrl.length < 120) return fromUrl
        val suffix = pkg.version.takeIf { it.isNotBlank() }?.let { "-$it" } ?: ""
        return "firmware$suffix.zip"
    }
}
