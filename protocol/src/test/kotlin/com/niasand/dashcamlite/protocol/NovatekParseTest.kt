package com.niasand.dashcamlite.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NovatekParseTest {
    @Test
    fun fileListMatchesDecompiledTags() {
        val files = NovatekClient.parseFileList(RecordingTransport.SAMPLE_FILE_LIST, "192.168.1.254")
        assertEquals(3, files.size)
        val normal = files[0]
        assertEquals(MediaKind.NORMAL, normal.kind)
        assertEquals("FILE20240101_120000_000.MOV", normal.name)
        assertEquals("A:\\CARDV\\MOVIE\\FILE20240101_120000_000.MOV", normal.remotePath)
        assertEquals(
            "http://192.168.1.254/CARDV/MOVIE/FILE20240101_120000_000.MOV",
            normal.url,
        )
        assertEquals(normal.url + "?custom=1&cmd=4001", normal.thumbUrl)
        assertEquals(1048576L, normal.size)

        assertEquals(MediaKind.EVENT, files[1].kind)
        assertEquals(MediaKind.PHOTO, files[2].kind)
        assertTrue(files[2].url.contains("/CARDV/PHOTO/"))
    }

    @Test
    fun settingsMapCmdToStatusValue() {
        val http = RecordingTransport()
        val client = NovatekClient("192.168.1.254", http)
        val items = client.readSettings()
        val resolution = items.first { it.id == "2002" }
        assertEquals("0", resolution.value)
        assertEquals("FULL HD", resolution.options.first { it.id == "0" }.label)
        val sound = items.first { it.id == "2007" }
        assertEquals("1", sound.value)
        val gsensor = items.first { it.id == "2011" }
        assertEquals("2", gsensor.value)
    }

    @Test
    fun commandUrlsMatchDeviceRequest() {
        val client = NovatekClient("192.168.1.254", RecordingTransport())
        assertEquals("http://192.168.1.254/?custom=1&cmd=3015", client.url(3015))
        assertEquals("http://192.168.1.254/?custom=1&cmd=3001&par=1", client.url(3001, par = "1"))
        assertEquals(
            "http://192.168.1.254/?custom=1&cmd=4003&str=A:\\CARDV\\MOVIE\\a.MOV",
            client.url(4003, str = "A:\\CARDV\\MOVIE\\a.MOV"),
        )
        assertEquals("http://192.168.1.254/?custom=1&cmd=3005&str=2024-01-01", client.url(3005, str = "2024-01-01"))
        assertEquals("rtsp://192.168.1.254/xxxx.mov", client.liveUrl())
        client.preferRtsp = false
        assertEquals("http://192.168.1.254:8192", client.liveUrl())
    }

    @Test
    fun resumeSequenceIsMovieModeThenRecordOn() {
        val http = RecordingTransport()
        val client = NovatekClient("192.168.1.254", http)
        client.resumeRecording()
        assertEquals(
            listOf(
                "http://192.168.1.254/?custom=1&cmd=3001&par=1",
                "http://192.168.1.254/?custom=1&cmd=2001&par=1",
            ),
            http.urls,
        )
    }

    @Test
    fun playbackModeStopsIntoModeTwo() {
        val http = RecordingTransport()
        NovatekClient("192.168.1.254", http).enterPlayback()
        assertEquals("http://192.168.1.254/?custom=1&cmd=3001&par=2", http.urls.single())
    }

    @Test
    fun handshakeReadsFirmwareAndSd() {
        val client = NovatekClient("192.168.1.254", RecordingTransport())
        val info = client.handshake()
        assertEquals("NT96660", info.firmware)
        assertEquals("QX302", info.model)
        assertTrue(info.recording)
        assertTrue(info.sdReady)
        assertEquals("CARDV_A1", info.ssid)
        assertEquals(1073741824L, info.freeBytes)
    }
}
