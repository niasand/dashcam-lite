package com.niasand.dashcamlite.protocol

import com.sun.net.httpserver.HttpServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder

class VendorAndMockTest {
    @Test
    fun mstarFileListAndRecordUrl() {
        val xml = """
            <files>
              <file>
                <name>/SD/Normal/F/a.MOV</name>
                <format time="12.5">mov</format>
                <size>100</size>
                <time>2024/01/01 00:00:00</time>
              </file>
            </files>
        """.trimIndent()
        val files = MstarClient.parseFiles(xml, "192.72.1.1", MediaKind.NORMAL)
        assertEquals("http://192.72.1.1/SD/Normal/F/a.MOV", files.single().url)
        assertTrue(files.single().thumbUrl!!.contains("thumb"))
        val http = RecordingTransport()
        val client = MstarClient("192.72.1.1", http)
        client.startRecord()
        assertTrue(http.urls.any { it.contains("action=get") && it.contains("status.record") })
    }

    @Test
    fun hisiliconUnregisterAndFolders() {
        val http = RecordingTransport()
        val client = HisiliconClient("192.168.11.1", http)
        client.close()
        assertTrue(http.urls.single().contains("operation=unregister"))
        val files = HisiliconClient.parseFiles(
            "<list><name>emr/lock.MP4</name></list>",
            "192.168.11.1",
            MediaKind.EVENT,
            CameraSide.FRONT,
        )
        assertEquals("http://192.168.11.1/emr/lock.MP4", files.single().url)
    }

    @Test
    fun gokeAndGrainCommandShape() {
        val http = RecordingTransport()
        GokeClient("192.168.63.9", http).resumeRecording()
        assertEquals(
            "http://192.168.63.9/cgi-bin/Control.cgi?type=system&action=setrecstatus&recstat=1",
            http.urls.single(),
        )
        http.urls.clear()
        GrainClient("192.168.201.1", http).heartbeat()
        assertEquals("http://192.168.201.1/?cmd=501", http.urls.single())
        http.urls.clear()
        GrainClient("192.168.201.1", http).resumeRecording()
        assertEquals("http://192.168.201.1/?cmd=101", http.urls.single())
    }

    @Test
    fun jieliFrameIsCtpLittleEndian() {
        val bytes = JieliClient.encode("VIDEO_CTRL", "PUT", mapOf("status" to "1"))
        assertEquals("CTP:", String(bytes.copyOfRange(0, 4)))
        val topicLen = ByteBuffer.wrap(bytes, 4, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt()
        assertEquals("VIDEO_CTRL".length, topicLen)
        val topic = String(bytes, 6, topicLen)
        assertEquals("VIDEO_CTRL", topic)
        val bodyLen = ByteBuffer.wrap(bytes, 6 + topicLen, 4).order(ByteOrder.LITTLE_ENDIAN).int
        val json = String(bytes, 10 + topicLen, bodyLen)
        assertTrue(json.contains("\"op\":\"PUT\""))
        assertTrue(json.contains("\"status\":\"1\""))
    }

    @Test
    fun ssidHints() {
        assertTrue(ProtocolDetector.looksLikeDashcam("CARDV-5G"))
        assertTrue(ProtocolDetector.looksLikeDashcam("\"Papago_A1\""))
        assertTrue(!ProtocolDetector.looksLikeDashcam("HomeWifi"))
    }

    @Test
    fun mockNovatekServerHandshakeListAndDownload() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val payload = "dashcam-bytes".toByteArray()
        server.createContext("/") { exchange ->
            val path = exchange.requestURI.path
            val query = exchange.requestURI.rawQuery.orEmpty()
            val body = when {
                path.contains("CARDV") -> payload
                query.contains("cmd=3015") -> RecordingTransport.SAMPLE_FILE_LIST.toByteArray()
                query.contains("cmd=3012") ->
                    "<Function><Cmd>3012</Cmd><Status>0</Status><String>FWTEST</String></Function>".toByteArray()
                query.contains("cmd=2016") ->
                    "<Function><Cmd>2016</Cmd><Status>0</Status><Value>0</Value></Function>".toByteArray()
                query.contains("cmd=3024") ->
                    "<Function><Cmd>3024</Cmd><Status>0</Status><Value>1</Value></Function>".toByteArray()
                query.contains("cmd=3017") ->
                    "<Function><Cmd>3017</Cmd><Status>0</Status><Value>10</Value></Function>".toByteArray()
                query.contains("cmd=3029") ->
                    "<Function><SSID>CARDV</SSID><PASSPHRASE>12345678</PASSPHRASE></Function>".toByteArray()
                else -> "<Function><Cmd>0</Cmd><Status>0</Status></Function>".toByteArray()
            }
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        try {
            val host = "127.0.0.1:${server.address.port}"
            val client = NovatekClient(host, OkHttpTransport())
            assertTrue(client.probe())
            val info = client.handshake()
            assertEquals("FWTEST", info.firmware)
            val videos = client.listFiles(MediaKind.NORMAL)
            assertEquals(1, videos.size)
            client.resumeRecording()
            assertTrue(client.commands.map { it.urlOrFrame }.takeLast(2).all { it.contains("cmd=3001") || it.contains("cmd=2001") })
            val dest = File.createTempFile("dashcam", ".mov")
            FileDownloader(OkHttpTransport()).download(videos.single().url, dest)
            assertEquals("dashcam-bytes", dest.readText())
            dest.delete()
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun downloadResumeAppendsPartial() {
        val transport = object : HttpTransport {
            override fun getText(url: String) = "ignored"
            override fun getBytes(url: String, rangeFrom: Long?): HttpBody {
                assertEquals(4L, rangeFrom)
                return HttpBody(206, "EF".toByteArray(), 2)
            }
        }
        val dest = File.createTempFile("part", ".bin")
        dest.writeText("ABCD")
        FileDownloader(transport).download("http://cam/a.MOV", dest)
        assertEquals("ABCDEF", dest.readText())
        dest.delete()
        assertEquals("bytes=4-", DownloadPlan.rangeHeader(4))
        assertTrue(DownloadPlan.appendResponse(206))
    }
}
