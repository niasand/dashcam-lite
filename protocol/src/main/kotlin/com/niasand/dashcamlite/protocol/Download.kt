package com.niasand.dashcamlite.protocol

import java.io.File

object DownloadPlan {
    fun rangeHeader(existingBytes: Long): String? =
        if (existingBytes > 0) "bytes=$existingBytes-" else null

    fun appendResponse(code: Int): Boolean = code == 206
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
