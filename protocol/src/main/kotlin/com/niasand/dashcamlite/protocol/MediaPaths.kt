package com.niasand.dashcamlite.protocol

import java.io.ByteArrayOutputStream
import java.io.InputStream

/** 把记录仪返回的路径拼成手机要请求的 HTTP 地址。 */
fun httpFileUrl(host: String, remotePath: String): String {
    val trimmed = remotePath.trim()
    if (trimmed.startsWith("http://", true) || trimmed.startsWith("https://", true)) return trimmed
    val normalized = trimmed.replace('\\', '/')
    val path = when {
        normalized.startsWith("/") -> normalized
        Regex("^[A-Za-z]:/").containsMatchIn(normalized) -> normalized.substring(normalized.indexOf('/'))
        else -> "/$normalized"
    }
    return "http://$host$path"
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
