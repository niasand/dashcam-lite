package com.niasand.dashcamlite.protocol

import java.io.ByteArrayOutputStream
import java.io.InputStream

/**
 * 把记录仪返回的路径拼成手机要请求的 HTTP 地址。
 * 原版取第一个 `/` 之后的部分，所以 `A:\...`、`SD:\...` 都会丢掉盘符。
 * 没有盘符时保留整段目录，避免把 `DCIM/MOVIE` 裁成 `/MOVIE`。
 */
fun httpFileUrl(host: String, remotePath: String): String {
    val trimmed = remotePath.trim()
    if (trimmed.startsWith("http://", true) || trimmed.startsWith("https://", true)) return trimmed
    val normalized = trimmed.replace('\\', '/')
    val slash = normalized.indexOf('/')
    val path = when {
        normalized.startsWith("/") -> normalized
        slash >= 0 && normalized.substring(0, slash).contains(':') -> normalized.substring(slash)
        slash >= 0 && !normalized.contains(':') -> "/$normalized"
        else -> "/${normalized.substringAfter(':')}"
    }
    return "http://$host$path"
}

/** 同一条文件在各芯片上可能出现的下载地址，按原版路径优先。 */
object DownloadUrls {
    fun candidates(vendor: String, host: String, remotePath: String, name: String): List<String> {
        val fileName = name.substringAfterLast('\\').substringAfterLast('/').ifBlank {
            remotePath.substringAfterLast('\\').substringAfterLast('/')
        }
        val primary = httpFileUrl(host, remotePath.ifBlank { fileName })
        val extras = when (vendor) {
            "novatek", "ark" -> listOf(
                "http://$host/DCIM/MOVIE/$fileName",
                "http://$host/DCIM/PHOTO/$fileName",
                "http://$host/CARDV/MOVIE/$fileName",
                "http://$host/CARDV/PHOTO/$fileName",
                "http://$host/MOVIE/$fileName",
                "http://$host/$fileName",
            )
            "mstar" -> listOf(
                "http://$host/SD/Normal/F/$fileName",
                "http://$host/SD/Event/F/$fileName",
                "http://$host/SD/Photo/F/$fileName",
                "http://$host/SD/Normal/$fileName",
            )
            "hisilicon" -> listOf(
                "http://$host/norm/$fileName",
                "http://$host/emr/$fileName",
                "http://$host/photo/$fileName",
                "http://$host/sd/norm/$fileName",
            )
            "goke" -> listOf(
                "http://$host/sdcard/DVR/VIDEO/$fileName",
                "http://$host/sdcard/DVR/EVENT/$fileName",
                "http://$host/sdcard/DVR/PIC/$fileName",
            )
            "grain" -> listOf("http://$host/$fileName")
            "jieli" -> listOf(
                "http://$host:8080/${remotePath.trim().trimStart('/')}",
                "http://$host:8080/$fileName",
            )
            else -> emptyList()
        }
        return (listOf(primary) + extras).map { it.trim() }.filter { it.startsWith("http") }.distinct()
    }
}

/** 记录仪用 XML/JSON 拒绝下载时，正文很短且不是媒体。 */
fun looksLikeCameraErrorBody(head: ByteArray, totalLength: Long): Boolean {
    if (totalLength <= 0L) return true
    if (totalLength > 256 * 1024) return false
    val text = head.toString(Charsets.UTF_8).trimStart()
    return text.startsWith("<") || text.startsWith("{") || text.startsWith("<?xml", true)
}

/**
 * 各芯片已知的实时画面地址。顺序是先试原版默认，再试同系列常见变体。
 * 播放器按这个顺序换路，直到画面出来。
 */
object LiveCatalog {
    fun of(vendor: String, host: String): List<String> = when (vendor) {
        "novatek", "ark" -> listOf(
            "rtsp://$host/xxxx.mov",
            "rtsp://$host/xxx.mov",
            "rtsp://$host/live",
            "http://$host:8192",
            "http://$host:8192/",
        )
        "mstar" -> listOf(
            "rtsp://$host/liveRTSP/av4",
            "rtsp://$host/liveRTSP/av0",
            "rtsp://$host/liveRTSP/av1",
            "rtsp://$host/liveRTSP/av2",
            "rtsp://$host/liveRTSP/v1",
        )
        "hisilicon" -> listOf(
            "rtsp://$host:554/livestream/0",
            "rtsp://$host:554/livestream/1",
            "rtsp://$host/livestream/0",
            "http://$host:8192",
        )
        "goke" -> listOf(
            "rtsp://$host/live/udp/ch1_0",
            "rtsp://$host/live/udp/ch3_0",
            "rtsp://$host/live/ch1_0",
        )
        "grain" -> listOf(
            "rtsp://$host/live/ch00_0",
            "rtsp://$host/live/ch01_0",
            "rtsp://$host:554/live/ch00_0",
        )
        "jieli" -> listOf(
            "rtsp://$host:554/avi_pcma_rt",
            "rtsp://$host:554/264_rt/XXX.sd",
            "rtsp://$host/avi_pcma_rt",
        )
        else -> listOf("rtsp://$host/xxxx.mov", "http://$host:8192")
    }

    fun isMjpeg(url: String): Boolean {
        val lower = url.lowercase()
        return lower.startsWith("http") && (":8192" in lower || lower.contains("mjpeg") || lower.contains("action=stream"))
    }
}

object JpegFrames {
    /** 从 MJPEG 或连续 JPEG 流里取出下一帧。 */
    fun next(input: InputStream): ByteArray? {
        val out = ByteArrayOutputStream()
        var prev = -1
        var started = false
        while (true) {
            val current = input.read()
            if (current < 0) return if (started && out.size() > 4) out.toByteArray() else null
            if (!started) {
                if (prev == 0xFF && current == 0xD8) {
                    started = true
                    out.write(0xFF)
                    out.write(0xD8)
                }
            } else {
                out.write(current)
                if (prev == 0xFF && current == 0xD9) return out.toByteArray()
            }
            prev = current
        }
    }
}

fun describeFailure(vendor: String, host: String, action: String, error: Throwable): String {
    val detail = error.message?.take(180) ?: error.javaClass.simpleName
    return "${action}失败（${vendor} @ ${host}）：${detail}"
}

fun explainDownloadFailure(vendor: String, host: String, name: String, error: Throwable): String {
    val chain = generateSequence(error) { it.cause }.toList()
    val storage = chain.any { isStorageFull(it) }
    val oom = chain.any { it is OutOfMemoryError }
    return when {
        oom -> "内存不足，没能保存「$name」。下载已改为分块写入，请关掉其他应用后再试。（$vendor @ $host）"
        storage -> "手机存储空间不足，无法保存「$name」。（$vendor @ $host）"
        else -> describeFailure(vendor, host, "下载 $name", error)
    }
}

fun isStorageFull(error: Throwable): Boolean {
    val message = error.message.orEmpty()
    return message.contains("ENOSPC", true) ||
        message.contains("No space left", true) ||
        message.contains("not enough space", true) ||
        message.contains("存储空间不足")
}

/** 缩略图只接受小图。录像开头不是 JPEG/PNG，不能拿去解码。 */
fun shouldDecodeAsImage(contentLength: Long?, magic: ByteArray): Boolean {
    if (contentLength != null && contentLength > MAX_IMAGE_BYTES) return false
    if (magic.size < 3) return false
    val jpeg = magic[0] == 0xFF.toByte() && magic[1] == 0xD8.toByte() && magic[2] == 0xFF.toByte()
    val png = magic[0] == 0x89.toByte() && magic[1] == 0x50.toByte() && magic[2] == 0x4E.toByte()
    return jpeg || png
}

const val MAX_IMAGE_BYTES = 2L * 1024 * 1024

object CameraHost {
    fun pick(gateway: String?, interfaceAddresses: List<String>, typed: String): String {
        val own = interfaceAddresses.map { it.substringBefore('%') }.toSet()
        val gw = gateway?.substringBefore('%')?.trim().orEmpty()
        if (gw.isNotEmpty() && gw !in own && isIpv4(gw)) return gw
        val typedHost = typed.trim()
        if (typedHost.isNotEmpty()) return typedHost
        return "192.168.1.254"
    }

    fun isIpv4(ip: String): Boolean {
        val parts = ip.split('.')
        return parts.size == 4 && parts.all { (it.toIntOrNull() ?: -1) in 0..255 }
    }
}
