package com.niasand.dashcamlite.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

class MediaPathTest {
    @Test
    fun driveLetterPathKeepsDirectoryAfterSlash() {
        assertEquals(
            "http://192.168.1.254/CARDV/MOVIE/a.MOV",
            httpFileUrl("192.168.1.254", "A:\\CARDV\\MOVIE\\a.MOV"),
        )
    }

    @Test
    fun sdVolumePrefixIsStrippedLikeTheOriginalApp() {
        assertEquals(
            "http://192.168.1.254/DCIM/MOVIE/a.MOV",
            httpFileUrl("192.168.1.254", "SD:\\DCIM\\MOVIE\\a.MOV"),
        )
    }

    @Test
    fun pathWithoutDriveLetterIsNotStripped() {
        assertEquals(
            "http://192.168.1.254/DCIM/MOVIE/a.MOV",
            httpFileUrl("192.168.1.254", "DCIM\\MOVIE\\a.MOV"),
        )
    }

    @Test
    fun absoluteAndHttpPaths() {
        assertEquals("http://192.72.1.1/SD/Normal/a.MOV", httpFileUrl("192.72.1.1", "/SD/Normal/a.MOV"))
        assertEquals("http://192.168.1.1:8080/mnt/a.MOV", httpFileUrl("192.168.1.1", "http://192.168.1.1:8080/mnt/a.MOV"))
    }

    @Test
    fun liveCandidatesKeepEveryFamily() {
        assertTrue(LiveCatalog.of("novatek", "192.168.1.254").first().endsWith("/xxxx.mov"))
        assertTrue(LiveCatalog.of("novatek", "192.168.1.254").any { it.contains(":8192") })
        assertTrue(LiveCatalog.of("mstar", "192.72.1.1").any { it.contains("liveRTSP/av4") })
        assertTrue(LiveCatalog.of("hisilicon", "192.168.11.1").any { it.contains("livestream/0") })
        assertTrue(LiveCatalog.of("goke", "192.168.63.9").any { it.contains("ch1_0") })
        assertTrue(LiveCatalog.of("grain", "192.168.201.1").any { it.contains("ch00_0") })
        assertTrue(LiveCatalog.of("jieli", "192.168.1.1").any { it.contains("avi_pcma_rt") })
        assertTrue(LiveCatalog.isMjpeg("http://192.168.1.254:8192"))
        assertFalse(LiveCatalog.isMjpeg("rtsp://192.168.1.254/xxxx.mov"))
        assertFalse(LiveCatalog.isMjpeg("http://192.168.1.254/DCIM/a.MOV"))
    }

    @Test
    fun jpegStreamReadsConsecutiveFrames() {
        val frame = byteArrayOf(0x00, 0xFF.toByte(), 0xD8.toByte(), 0x11, 0xFF.toByte(), 0xD9.toByte(), 0xFF.toByte(), 0xD8.toByte(), 0x22, 0xFF.toByte(), 0xD9.toByte())
        val input = ByteArrayInputStream(frame)
        val first = JpegFrames.next(input)
        val second = JpegFrames.next(input)
        assertEquals(0xFF, first!![0].toInt() and 0xFF)
        assertEquals(0xD8, first[1].toInt() and 0xFF)
        assertEquals(0xD9, first.last().toInt() and 0xFF)
        assertEquals(0x22, second!![2].toInt() and 0xFF)
    }

    @Test
    fun cameraHostPrefersGatewayOverTypedDefaultAndIgnoresOwnAddress() {
        assertEquals(
            "192.72.1.1",
            CameraHost.pick("192.72.1.1", listOf("192.72.1.50"), "192.168.1.254"),
        )
        assertEquals(
            "192.168.1.254",
            CameraHost.pick("192.168.1.20", listOf("192.168.1.20"), "192.168.1.254"),
        )
        assertEquals("10.0.0.1", CameraHost.pick(null, emptyList(), "10.0.0.1"))
    }

    @Test
    fun downloadCandidatesKeepPrimaryAndFamilyFallbacks() {
        val novatek = DownloadUrls.candidates(
            "novatek",
            "192.168.1.254",
            "SD:\\DCIM\\MOVIE\\a.MOV",
            "a.MOV",
        )
        assertEquals("http://192.168.1.254/DCIM/MOVIE/a.MOV", novatek.first())
        assertTrue(novatek.any { it.endsWith("/CARDV/MOVIE/a.MOV") })
        val mstar = DownloadUrls.candidates("mstar", "192.72.1.1", "/SD/Normal/F/a.MOV", "a.MOV")
        assertEquals("http://192.72.1.1/SD/Normal/F/a.MOV", mstar.first())
        val jieli = DownloadUrls.candidates("jieli", "192.168.1.1", "mnt/sd/a.MOV", "a.MOV")
        assertTrue(jieli.any { it.startsWith("http://192.168.1.1:8080/") })
    }

    @Test
    fun xmlErrorBodyIsNotAVideo() {
        assertTrue(looksLikeCameraErrorBody("<Function><Status>-1</Status></Function>".toByteArray(), 40))
        assertFalse(looksLikeCameraErrorBody(byteArrayOf(0x00, 0x00, 0x00, 0x18, 0x66, 0x74, 0x79, 0x70), 2_000_000))
    }

    @Test
    fun downloadFirstSkipsXmlAndUsesTheNextUrl() {
        val http = object : HttpTransport {
            val hits = mutableListOf<String>()
            override fun getText(url: String) = getBytes(url).text()
            override fun getBytes(url: String, rangeFrom: Long?): HttpBody {
                hits += url
                assertEquals(null, rangeFrom)
                return if (url.contains("/CARDV/")) {
                    HttpBody(200, "ftyp-video-bytes-padding".toByteArray(), 24)
                } else {
                    HttpBody(200, "<Function><Status>-1</Status></Function>".toByteArray(), 40)
                }
            }
        }
        val dest = java.io.File.createTempFile("download", ".mov")
        val used = FileDownloader(http).downloadFirst(
            listOf(
                "http://192.168.1.254/DCIM/MOVIE/a.MOV",
                "http://192.168.1.254/CARDV/MOVIE/a.MOV",
            ),
            dest,
        )
        assertEquals("http://192.168.1.254/CARDV/MOVIE/a.MOV", used)
        assertEquals(2, http.hits.size)
        dest.delete()
    }

    @Test
    fun failureTextNamesChipAndHost() {
        val text = describeFailure("novatek", "192.168.1.254", "预览", IllegalStateException("HTTP 404 http://192.168.1.254/a.MOV"))
        assertTrue(text.contains("novatek"))
        assertTrue(text.contains("192.168.1.254"))
        assertTrue(text.contains("404"))
    }
}