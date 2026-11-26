package com.niasand.dashcamlite.protocol

import java.io.File
import java.io.InputStream
import java.io.OutputStream

object DownloadPlan {
    fun rangeHeader(existingBytes: Long): String? =
        if (existingBytes > 0) "bytes=$existingBytes-" else null

    fun appendResponse(code: Int): Boolean = code == 206
}

object ChunkCopy {
    const val BUFFER_BYTES = 8 * 1024

    /** 每次最多读 [BUFFER_BYTES]，不会把整个文件放进一个数组。 */
    fun copy(input: InputStream, output: OutputStream, onBytes: (Long) -> Unit = {}): Long {
        val buffer = ByteArray(BUFFER_BYTES)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            if (read > 0) {
                output.write(buffer, 0, read)
                total += read
                onBytes(total)
            }
        }
        return total
    }
}

class FileDownloader(private val http: HttpTransport) {
    fun download(url: String, dest: File, onProgress: (Long, Long) -> Unit = { _, _ -> }) {
        try {
            http.writeToFile(url, dest, onProgress)
        } catch (error: Throwable) {
            throw IllegalStateException("下载失败 ${url.substringBefore('?')}：${error.message}", error)
        }
    }

    /**
     * 记录仪多数不支持 Range，带 Range 会直接 416。这里每次都整文件重下，
     * 并在正文是 XML 错误时换下一个地址。
     */
    fun downloadFirst(urls: List<String>, dest: File, onProgress: (Long, Long) -> Unit = { _, _ -> }): String {
        val targets = urls.distinct()
        var last: Throwable? = null
        for (url in targets) {
            try {
                if (dest.exists()) dest.delete()
                http.writeFresh(url, dest, onProgress)
                val head = dest.inputStream().use { it.readNBytes(200) }
                if (looksLikeCameraErrorBody(head, dest.length())) {
                    last = IllegalStateException("HTTP 200 但不是视频或照片 $url")
                    continue
                }
                return url
            } catch (error: Throwable) {
                last = error
            }
        }
        val tried = targets.joinToString(" | ") { it.substringBefore('?') }
        throw IllegalStateException("下载失败 $tried：${last?.message ?: "没有可用地址"}", last)
    }
}
