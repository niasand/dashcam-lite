package com.niasand.dashcamlite.protocol

import java.io.File

object DownloadPlan {
    fun rangeHeader(existingBytes: Long): String? =
        if (existingBytes > 0) "bytes=$existingBytes-" else null

    fun appendResponse(code: Int): Boolean = code == 206
}

class FileDownloader(private val http: HttpTransport) {
    fun download(url: String, dest: File, onProgress: (Long, Long) -> Unit = { _, _ -> }) {
        dest.parentFile?.mkdirs()
        val existing = if (dest.exists()) dest.length() else 0L
        val body = http.getBytes(url, if (existing > 0) existing else null)
        when {
            body.code == 206 && existing > 0 -> dest.appendBytes(body.bytes)
            body.code in 200..299 -> dest.writeBytes(body.bytes)
            else -> error("下载失败 HTTP ${body.code}")
        }
        val total = body.contentLength?.let { len ->
            if (body.code == 206) existing + len else len
        } ?: dest.length()
        onProgress(dest.length(), total)
    }
}
