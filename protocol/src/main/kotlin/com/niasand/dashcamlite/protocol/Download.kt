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
}
