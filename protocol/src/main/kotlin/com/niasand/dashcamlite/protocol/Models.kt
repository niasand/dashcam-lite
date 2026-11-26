package com.niasand.dashcamlite.protocol

enum class MediaKind { NORMAL, EVENT, PHOTO, PARK }

enum class CameraSide { FRONT, REAR, UNKNOWN }

data class MediaFile(
    val name: String,
    val remotePath: String,
    val url: String,
    val thumbUrl: String?,
    val size: Long,
    val time: String,
    val kind: MediaKind,
    val camera: CameraSide = CameraSide.UNKNOWN,
    val durationSec: Int = -1,
)

data class DeviceInfo(
    val vendor: String,
    val host: String,
    val firmware: String,
    val recording: Boolean,
    val sdReady: Boolean,
    val sdStatusText: String,
    val freeBytes: Long?,
    val ssid: String?,
    val model: String?,
)

data class SettingItem(
    val id: String,
    val label: String,
    val value: String,
    val options: List<SettingOption> = emptyList(),
)

data class SettingOption(val id: String, val label: String)

data class CommandTrace(val method: String, val urlOrFrame: String)

interface HttpTransport {
    fun getText(url: String): String
    fun getBytes(url: String, rangeFrom: Long? = null): HttpBody

    fun writeToFile(url: String, dest: java.io.File, onProgress: (Long, Long) -> Unit = { _, _ -> }) {
        dest.parentFile?.mkdirs()
        val existing = if (dest.exists()) dest.length() else 0L
        val body = getBytes(url, if (existing > 0) existing else null)
        when {
            body.code == 206 && existing > 0 -> dest.appendBytes(body.bytes)
            body.code in 200..299 -> dest.writeBytes(body.bytes)
            else -> error("HTTP ${body.code} $url")
        }
        val total = body.contentLength?.let { len ->
            if (body.code == 206) existing + len else len
        } ?: dest.length()
        onProgress(dest.length(), total)
    }
}

data class HttpBody(
    val code: Int,
    val bytes: ByteArray,
    val contentLength: Long?,
) {
    fun text(): String = bytes.toString(Charsets.UTF_8)
}

class OkHttpTransport(
    private val client: okhttp3.OkHttpClient = defaultClient(),
) : HttpTransport {
    override fun getText(url: String): String = getBytes(url).let { body ->
        if (body.code !in 200..299) error("HTTP ${body.code} $url")
        body.text()
    }

    override fun getBytes(url: String, rangeFrom: Long?): HttpBody {
        val builder = okhttp3.Request.Builder().url(url).header("Connection", "close")
        if (rangeFrom != null && rangeFrom > 0) {
            builder.header("Range", "bytes=$rangeFrom-")
        }
        client.newCall(builder.build()).execute().use { response ->
            val bytes = response.body?.bytes() ?: ByteArray(0)
            val len = response.header("Content-Length")?.toLongOrNull()
            return HttpBody(response.code, bytes, len)
        }
    }

    override fun writeToFile(url: String, dest: java.io.File, onProgress: (Long, Long) -> Unit) {
        dest.parentFile?.mkdirs()
        val existing = if (dest.exists()) dest.length() else 0L
        val builder = okhttp3.Request.Builder().url(url).header("Connection", "close")
        if (existing > 0) builder.header("Range", "bytes=$existing-")
        client.newCall(builder.build()).execute().use { response ->
            val body = response.body ?: error("HTTP ${response.code} $url 没有响应体")
            val code = response.code
            when {
                code == 206 && existing > 0 -> java.io.FileOutputStream(dest, true).use { output ->
                    body.byteStream().use { it.copyTo(output) }
                }
                code in 200..299 -> dest.outputStream().use { output ->
                    body.byteStream().use { it.copyTo(output) }
                }
                else -> error("HTTP $code $url")
            }
            val reported = response.header("Content-Length")?.toLongOrNull()
            val total = when {
                code == 206 && reported != null -> existing + reported
                reported != null && code != 206 -> reported
                else -> dest.length()
            }
            onProgress(dest.length(), total)
        }
    }

    companion object {
        fun defaultClient(): okhttp3.OkHttpClient {
            val seconds = java.util.concurrent.TimeUnit.SECONDS
            return okhttp3.OkHttpClient.Builder()
                .connectTimeout(10, seconds)
                .readTimeout(60, seconds)
                .writeTimeout(60, seconds)
                .retryOnConnectionFailure(true)
                .build()
        }
    }
}

interface DashcamClient {
    val vendor: String
    val host: String
    val commands: List<CommandTrace>

    fun probe(): Boolean
    fun handshake(): DeviceInfo
    fun heartbeat()
    fun listFiles(kind: MediaKind): List<MediaFile>
    fun delete(file: MediaFile)
    fun liveUrl(): String
    fun liveCandidates(): List<String> = LiveCatalog.of(vendor, host)
    fun enterPlayback()
    fun startRecord()
    fun stopRecord()
    /** 离开预览/回放、退到后台或进程退出时，让记录仪回到正常录像。 */
    fun resumeRecording()
    fun syncTime(now: java.util.Date = java.util.Date())
    fun formatSd()
    fun readSettings(): List<SettingItem>
    fun writeSetting(item: SettingItem, optionId: String)
    fun switchCamera(): String
    fun close() {}
}

object XmlSnips {
    fun texts(xml: String, tag: String): List<String> {
        val pattern = Regex("<$tag\\b[^>]*>([\\s\\S]*?)</$tag>", RegexOption.IGNORE_CASE)
        return pattern.findAll(xml).map { unescape(it.groupValues[1].trim()) }.toList()
    }

    fun first(xml: String, tag: String): String? = texts(xml, tag).firstOrNull()

    fun attr(xml: String, tag: String, attribute: String): String? {
        val pattern = Regex("<$tag\\b([^>]*)>", RegexOption.IGNORE_CASE)
        val block = pattern.find(xml)?.groupValues?.get(1) ?: return null
        val attr = Regex("""\b$attribute\s*=\s*"([^"]*)"""", RegexOption.IGNORE_CASE)
        return attr.find(block)?.groupValues?.get(1)
    }

    private fun unescape(value: String): String = value
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&amp;", "&")
        .replace("&quot;", "\"")
}

fun cameraFromName(name: String): CameraSide {
    val upper = name.uppercase()
    return when {
        upper.contains("REAR") || upper.contains("BACK") ||
            Regex("(^|[\\W_])R[\\W_]").containsMatchIn(upper) ||
            upper.contains("_B.") || upper.contains("BACK_") || upper.contains("B_") -> CameraSide.REAR
        upper.contains("FRONT") || upper.contains("_F.") -> CameraSide.FRONT
        else -> CameraSide.UNKNOWN
    }
}
