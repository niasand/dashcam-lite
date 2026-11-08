package com.niasand.dashcamlite.protocol

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.Charset
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** SigmaStar / MStar CGI。类：com.tima.lib.dr.res.devices.mstar.device.DeviceApi */
class MstarClient(override val host: String, private val http: HttpTransport) : DashcamClient {
    override val vendor = "mstar"
    override val commands = mutableListOf<CommandTrace>()
    private var rear = false
    var rtspPath: String = "/liveRTSP/av4"

    fun cgi(action: String, property: String? = null, value: String? = null, extra: String = ""): String {
        val sb = StringBuilder("http://$host/cgi-bin/Config.cgi?action=$action")
        if (property != null) sb.append("&property=").append(property)
        if (value != null) sb.append("&value=").append(value)
        if (extra.isNotEmpty()) sb.append(extra)
        return sb.toString()
    }

    private fun get(url: String): String {
        commands += CommandTrace("GET", url)
        return http.getText(url)
    }

    override fun probe(): Boolean = runCatching {
        val text = get(cgi("get", "Camera.Preview.MJPEG.status.record"))
        text.startsWith("0") || text.contains("record=")
    }.getOrDefault(false)

    override fun handshake(): DeviceInfo {
        val record = get(cgi("get", "Camera.Preview.MJPEG.status.record"))
        val recording = record.substringAfter("record=", "").substringBefore('\n').trim() == "Recording"
        val menu = runCatching { get("http://$host/cdv/cammenu.xml") }.getOrNull().orEmpty()
        val fw = Regex("FW[^\n<]*", RegexOption.IGNORE_CASE).find(menu)?.value ?: "MStar"
        val sd = runCatching { get(cgi("get", "Camera.Menu.GetSD")) }.getOrNull().orEmpty()
        return DeviceInfo(vendor, host, fw, recording, sd.contains("OK") || sd.startsWith("0"), if (sd.isBlank()) "未知" else "已应答", null, null, "SigmaStar")
    }

    override fun heartbeat() {
        get(cgi("get", "Camera.Preview.MJPEG.status.record"))
    }

    override fun listFiles(kind: MediaKind): List<MediaFile> {
        val property = when (kind) {
            MediaKind.NORMAL -> "Normal"
            MediaKind.EVENT -> "Event"
            MediaKind.PARK -> "Park"
            MediaKind.PHOTO -> "Photo"
        }
        val format = if (kind == MediaKind.PHOTO) "jpeg" else "all"
        val action = if (rear) "reardir" else "dir"
        val url = "http://$host/cgi-bin/Config.cgi?&action=$action&property=$property&format=$format&count=20&from=0&backward="
        return parseFiles(get(url), host, kind)
    }

    override fun delete(file: MediaFile) {
        get(cgi("del", file.remotePath))
    }

    override fun liveUrl() = "rtsp://$host$rtspPath"

    override fun enterPlayback() {
        stopRecord()
    }

    override fun startRecord() {
        val status = get(cgi("get", "Camera.Preview.MJPEG.status.record"))
        if (!status.contains("Recording")) get(cgi("set", "Video", "record"))
    }

    override fun stopRecord() {
        val status = get(cgi("get", "Camera.Preview.MJPEG.status.record"))
        if (status.contains("Recording")) get(cgi("set", "Video", "record"))
    }

    override fun resumeRecording() = startRecord()

    override fun syncTime(now: Date) {
        val stamp = SimpleDateFormat("yyyy\$MM\$dd\$HH\$mm\$ss", Locale.ENGLISH).format(now)
        get(cgi("set", "TimeSettings", stamp))
    }

    override fun formatSd() {
        get(cgi("set", "SD0", "format"))
    }

    override fun readSettings(): List<SettingItem> = listOf(
        SettingItem("Videores", "录像分辨率", "", listOf(SettingOption("1080P", "1080P"), SettingOption("720P", "720P"))),
        SettingItem("LoopingVideo", "循环录像", "", listOf(SettingOption("1MIN", "1分钟"), SettingOption("3MIN", "3分钟"), SettingOption("5MIN", "5分钟"))),
    )

    override fun writeSetting(item: SettingItem, optionId: String) {
        get(cgi("set", item.id, optionId))
    }

    override fun switchCamera(): String {
        rear = !rear
        val id = if (rear) "rear" else "front"
        get(cgi("setcamid", "Camera.Preview.Source.1.Camid", id))
        return id
    }

    companion object {
        fun parseFiles(xml: String, host: String, kind: MediaKind): List<MediaFile> {
            val blocks = Regex("<file\\b[^>]*>([\\s\\S]*?)</file>", RegexOption.IGNORE_CASE).findAll(xml)
            return blocks.mapNotNull { match ->
                val body = match.groupValues[1]
                val path = XmlSnips.first(body, "name") ?: return@mapNotNull null
                val name = path.substringAfterLast('/')
                val size = XmlSnips.first(body, "size")?.toLongOrNull() ?: 0L
                val time = XmlSnips.first(body, "time").orEmpty()
                val duration = XmlSnips.attr(body, "format", "time")?.toFloatOrNull()?.toInt() ?: -1
                val url = "http://$host$path"
                val thumb = if (name.uppercase().endsWith(".MOV")) {
                    url.replace("SD1", "thumb").replace("SD", "thumb")
                } else url
                MediaFile(name, path, url, thumb, size, time, kind, cameraFromName(path), duration)
            }.toList()
        }
    }
}

/** 海思 hisnet。类：com.tima.lib.dr.res.devices.hisilicon.device.DeviceApi */
class HisiliconClient(override val host: String, private val http: HttpTransport) : DashcamClient {
    override val vendor = "hisilicon"
    override val commands = mutableListOf<CommandTrace>()
    private var rear = false

    private fun get(path: String): String {
        val url = "http://$host/cgi-bin/hisnet/$path"
        commands += CommandTrace("GET", url)
        return http.getText(url)
    }

    override fun probe(): Boolean = runCatching {
        get("getdeviceattr.cgi").contains("Status", ignoreCase = true) ||
            get("getdeviceattr.cgi").isNotBlank()
    }.getOrDefault(false)

    override fun handshake(): DeviceInfo {
        val attr = get("getdeviceattr.cgi")
        val fw = XmlSnips.first(attr, "softversion")
            ?: XmlSnips.first(attr, "firmware")
            ?: "HiSilicon"
        val phone = "0.0.0.0"
        get("client.cgi?&-operation=register&-ip=$phone")
        val sd = runCatching { get("getsdstatus.cgi") }.getOrNull().orEmpty()
        val work = runCatching { get("getworkstate.cgi") }.getOrNull().orEmpty()
        return DeviceInfo(vendor, host, fw, work.contains("record", true), sd.contains("1") || sd.contains("ok", true), sd.ifBlank { "未知" }, null, null, "HiSilicon")
    }

    override fun heartbeat() {
        get("checkconnect.cgi?&-ip=0.0.0.0")
    }

    override fun listFiles(kind: MediaKind): List<MediaFile> {
        val dir = when (kind) {
            MediaKind.NORMAL -> if (rear) "back_norm" else "norm"
            MediaKind.EVENT -> if (rear) "back_emr" else "emr"
            MediaKind.PARK -> "park"
            MediaKind.PHOTO -> "photo"
        }
        val xml = get("getdirfilelist.cgi?&-dir=$dir&-start=0&-end=99")
        return parseFiles(xml, host, kind, if (rear) CameraSide.REAR else CameraSide.FRONT)
    }

    override fun delete(file: MediaFile) {
        get("deletefile.cgi?&-name=${file.remotePath}")
    }

    override fun liveUrl() = "rtsp://$host:554/livestream/${if (rear) 1 else 0}"

    override fun enterPlayback() {
        get("workmodecmd.cgi?&-cmd=stop")
    }

    override fun startRecord() {
        get("setworkmode.cgi?&-workmode=NORM_REC")
        get("workmodecmd.cgi?&-cmd=start")
    }

    override fun stopRecord() {
        get("workmodecmd.cgi?&-cmd=stop")
    }

    override fun resumeRecording() {
        // DeviceKit.disConnect 会 unregister；预览离开时 PlayActivity 再 startRecord
        startRecord()
    }

    override fun syncTime(now: Date) {
        val stamp = SimpleDateFormat("yyyyMMddHHmmss", Locale.ENGLISH).format(now)
        get("setsystime.cgi?&-time=$stamp")
    }

    override fun formatSd() {
        get("sdcommand.cgi?&-format=")
    }

    override fun readSettings(): List<SettingItem> = emptyList()

    override fun writeSetting(item: SettingItem, optionId: String) {
        get("setcommparam.cgi?&-type=${item.id}&-value=$optionId")
    }

    override fun switchCamera(): String {
        rear = !rear
        return if (rear) "rear" else "front"
    }

    override fun close() {
        runCatching { get("client.cgi?&-operation=unregister&-ip=0.0.0.0") }
    }

    companion object {
        fun parseFiles(xml: String, host: String, kind: MediaKind, camera: CameraSide): List<MediaFile> {
            val names = XmlSnips.texts(xml, "name").ifEmpty { XmlSnips.texts(xml, "NAME") }
            return names.map { name ->
                val path = if (name.startsWith("/")) name else "/$name"
                MediaFile(path.substringAfterLast('/'), path, "http://$host$path", null, 0, "", kind, camera)
            }
        }
    }
}

/** 国科微 GK。类：com.tima.app.dr.goke.device.DeviceApi，IP 默认 192.168.63.9 */
class GokeClient(override val host: String, private val http: HttpTransport) : DashcamClient {
    override val vendor = "goke"
    override val commands = mutableListOf<CommandTrace>()

    private fun get(query: String): String {
        val url = "http://$host/cgi-bin/Control.cgi?$query"
        commands += CommandTrace("GET", url)
        return http.getText(url)
    }

    override fun probe(): Boolean = runCatching {
        get("type=system&action=getversion").contains("Result", true) ||
            get("type=system&action=getversion").contains("ok", true)
    }.getOrDefault(false)

    override fun handshake(): DeviceInfo {
        val ver = get("type=system&action=getversion")
        val fw = XmlSnips.first(ver, "Firmware_version") ?: ver.lineValue("Firmware_version") ?: "Goke"
        val rec = runCatching { get("type=system&action=getrecstatus") }.getOrNull().orEmpty()
        return DeviceInfo(vendor, host, fw, rec.contains("1"), true, "已连接", null, null, "Goke")
    }

    override fun heartbeat() {
        get("type=hbt")
    }

    override fun listFiles(kind: MediaKind): List<MediaFile> {
        val query = when (kind) {
            MediaKind.EVENT -> "type=file&action=searchevent&index=0"
            MediaKind.PHOTO -> "type=image&action=searchimg&index=0"
            else -> "type=file&action=searchfile&index=0"
        }
        val body = get(query)
        val folder = when (kind) {
            MediaKind.EVENT -> "/sdcard/DVR/EVENT/"
            MediaKind.PHOTO -> "/sdcard/DVR/PIC/"
            else -> "/sdcard/DVR/VIDEO/"
        }
        val names = XmlSnips.texts(body, "name").ifEmpty {
            body.lines().map { it.trim() }.filter { it.endsWith(".mp4", true) || it.endsWith(".mov", true) || it.endsWith(".jpg", true) || it.endsWith(".ts", true) }
        }
        return names.map { raw ->
            val name = raw.substringAfterLast('/')
            val path = if (raw.startsWith("/")) raw else folder + name
            MediaFile(name, path, "http://$host$path", null, 0, "", kind, cameraFromName(name))
        }
    }

    override fun delete(file: MediaFile) {
        val action = if (file.kind == MediaKind.PHOTO) "deleteimg" else "deletefile"
        val type = if (file.kind == MediaKind.PHOTO) "image" else "file"
        get("type=$type&action=$action&name=${file.name}")
    }

    override fun liveUrl() = "rtsp://$host/live/udp/ch1_0"

    override fun enterPlayback() = stopRecord()
    override fun startRecord() {
        get("type=system&action=setrecstatus&recstat=1")
    }
    override fun stopRecord() {
        get("type=system&action=setrecstatus&recstat=0")
    }
    override fun resumeRecording() = startRecord()
    override fun syncTime(now: Date) {
        val stamp = SimpleDateFormat("yyyy-MM-dd_HH:mm:ss", Locale.ENGLISH).format(now)
        get("type=system&action=setdatetime&$stamp")
    }
    override fun formatSd() {
        get("type=system&action=formattf")
    }
    override fun readSettings() = listOf(
        SettingItem("resolution", "录像分辨率", "", listOf(SettingOption("0", "1080P"), SettingOption("1", "720P"))),
        SettingItem("rectime", "循环录像", "", listOf(SettingOption("1", "1分钟"), SettingOption("3", "3分钟"))),
    )
    override fun writeSetting(item: SettingItem, optionId: String) {
        get("type=param&action=setrecord&${item.id}=$optionId")
    }
    override fun switchCamera() = "front"
}

/** 凌通/全志一类 Grain CGI。类：com.tima.lib.dr.res.devices.grain.device.beans.DeviceRequest，IP 192.168.201.1 */
class GrainClient(override val host: String, private val http: HttpTransport) : DashcamClient {
    override val vendor = "grain"
    override val commands = mutableListOf<CommandTrace>()

    private fun get(cmd: Int, type: Int? = null, param: String? = null, value: String? = null): String {
        val sb = StringBuilder("http://$host/?cmd=$cmd")
        if (type != null) sb.append("&type=").append(type)
        if (param != null) sb.append("&param=").append(param)
        if (value != null) sb.append("&value=").append(value)
        val url = sb.toString()
        commands += CommandTrace("GET", url)
        return http.getText(url)
    }

    override fun probe(): Boolean = runCatching {
        get(305).contains("0")
    }.getOrDefault(false)

    override fun handshake(): DeviceInfo {
        val info = get(305)
        val fw = XmlSnips.first(info, "version") ?: "Grain"
        val rec = get(103)
        return DeviceInfo(vendor, host, fw, rec.contains("1"), true, "已连接", null, null, "Grain")
    }

    override fun heartbeat() {
        get(501)
    }

    override fun listFiles(kind: MediaKind): List<MediaFile> {
        val type = when (kind) {
            MediaKind.NORMAL -> 0
            MediaKind.EVENT -> 1
            MediaKind.PARK -> 2
            MediaKind.PHOTO -> 3
        }
        val xml = get(201, type = type)
        val names = XmlSnips.texts(xml, "name").ifEmpty { XmlSnips.texts(xml, "path") }
        return names.map { path ->
            val name = path.substringAfterLast('/')
            val url = if (path.startsWith("http")) path else "http://$host$path"
            MediaFile(name, path, url, null, 0, "", kind, cameraFromName(name))
        }
    }

    override fun delete(file: MediaFile) {
        get(202, param = file.remotePath)
    }

    override fun liveUrl() = "rtsp://$host/live/ch00_0"
    override fun enterPlayback() = stopRecord()
    override fun startRecord() {
        get(101)
    }
    override fun stopRecord() {
        get(102)
    }
    override fun resumeRecording() = startRecord()
    override fun syncTime(now: Date) {
        val stamp = SimpleDateFormat("yyyy-MM-dd\$HH:mm:ss", Locale.ENGLISH).format(now)
        get(306, param = stamp)
    }
    override fun formatSd() {
        get(308)
    }
    override fun readSettings(): List<SettingItem> = listOf(
        SettingItem("video_resolution", "录像分辨率", "", emptyList()),
        SettingItem("looping_video", "循环录像", "", emptyList()),
    )
    override fun writeSetting(item: SettingItem, optionId: String) {
        get(301, param = item.id, value = optionId)
    }
    override fun switchCamera(): String {
        get(104)
        return "toggled"
    }
}

/**
 * 杰理 TCP 3333，帧头 ASCII "CTP:"。
 * 类：com.tima.app.dr.jieli.device.beans.BaseData / DeviceApi。
 * 文件正文在 http://ip:8080/ 下。
 */
class JieliClient(override val host: String, private val http: HttpTransport) : DashcamClient {
    override val vendor = "jieli"
    override val commands = mutableListOf<CommandTrace>()
    private var socket: Socket? = null

    override fun probe(): Boolean = runCatching {
        connect()
        val reply = request("APP_ACCESS", "PUT", mapOf("type" to "0", "ver" to "1.0"))
        reply.contains("errorno") || reply.isNotBlank() || true
    }.getOrDefault(false)

    override fun handshake(): DeviceInfo {
        connect()
        request("APP_ACCESS", "PUT", mapOf("type" to "0", "ver" to "1.0"))
        val rec = request("VIDEO_CTRL", "GET")
        val recording = rec.contains("\"status\":\"1\"") || rec.contains("\"status\": \"1\"")
        return DeviceInfo(vendor, host, "Jieli", recording, true, "已连接", null, null, "Jieli")
    }

    override fun heartbeat() {
        request("CTP_KEEP_ALIVE", "GET")
    }

    override fun listFiles(kind: MediaKind): List<MediaFile> {
        val reply = request("FORWARD_MEDIA_FILES_LIST", "PUT", mapOf("type" to "1"))
        val path = Regex(""""path"\s*:\s*"([^"]+)"""").find(reply)?.groupValues?.get(1)
        val json = if (path != null) {
            val url = "http://$host:8080/$path"
            commands += CommandTrace("GET", url)
            http.getText(url)
        } else reply
        return parseFileJson(json).filter {
            when (kind) {
                MediaKind.NORMAL -> it.kind == MediaKind.NORMAL
                MediaKind.EVENT -> it.kind == MediaKind.EVENT
                MediaKind.PHOTO -> it.kind == MediaKind.PHOTO
                MediaKind.PARK -> it.kind == MediaKind.PARK
            }
        }
    }

    override fun delete(file: MediaFile) {
        request("FILES_DELETE", "PUT", mapOf("path_0" to file.remotePath))
    }

    override fun liveUrl() = "rtsp://$host:554/avi_pcma_rt"
    override fun enterPlayback() = stopRecord()
    override fun startRecord() {
        request("VIDEO_CTRL", "PUT", mapOf("status" to "1"))
    }
    override fun stopRecord() {
        request("VIDEO_CTRL", "PUT", mapOf("status" to "0"))
    }
    override fun resumeRecording() = startRecord()
    override fun syncTime(now: Date) {
        val stamp = SimpleDateFormat("yyyyMMddhhmmss", Locale.US).format(now)
        request("DATE_TIME", "PUT", mapOf("date" to stamp))
    }
    override fun formatSd() {
        request("FORMAT", "PUT")
    }
    override fun readSettings() = emptyList<SettingItem>()
    override fun writeSetting(item: SettingItem, optionId: String) {}
    override fun switchCamera() = "front"
    override fun close() {
        runCatching { socket?.close() }
        socket = null
    }

    fun connect() {
        if (socket?.isConnected == true && socket?.isClosed == false) return
        val opened = Socket()
        opened.connect(InetSocketAddress(host.substringBefore(':'), 3333), 3000)
        opened.soTimeout = 4000
        socket = opened
    }

    fun request(topic: String, op: String, params: Map<String, String> = emptyMap()): String {
        val frame = encode(topic, op, params)
        commands += CommandTrace("TCP", "$topic $op ${params.entries.joinToString()}")
        val current = socket ?: return ""
        current.getOutputStream().write(frame)
        current.getOutputStream().flush()
        return runCatching { readFrame(current) }.getOrDefault("")
    }

    companion object {
        val IDENTIFIER = byteArrayOf(67, 84, 80, 58) // "CTP:"

        fun encode(topic: String, op: String, params: Map<String, String>): ByteArray {
            val json = buildString {
                append("{\"op\":\"$op\"")
                if (params.isNotEmpty()) {
                    append(",\"param\":{")
                    append(params.entries.joinToString(",") { (k, v) -> "\"$k\":\"$v\"" })
                    append("}")
                }
                append("}")
            }
            val topicBytes = topic.toByteArray(Charsets.UTF_8)
            val body = json.toByteArray(Charsets.UTF_8)
            val out = ByteArrayOutputStream()
            val data = DataOutputStream(out)
            out.write(IDENTIFIER)
            data.writeShort(littleShort(topicBytes.size.toShort()))
            data.write(topicBytes)
            data.writeInt(littleInt(body.size))
            data.write(body)
            data.flush()
            return out.toByteArray()
        }

        fun littleShort(value: Short): Int {
            val ordered = ByteBuffer.allocate(2).order(ByteOrder.BIG_ENDIAN).putShort(value)
                .order(ByteOrder.LITTLE_ENDIAN).getShort(0)
            return ordered.toInt() and 0xFFFF
        }

        fun littleInt(value: Int): Int {
            return ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(value)
                .order(ByteOrder.LITTLE_ENDIAN).getInt(0)
        }

        fun readFrame(socket: Socket): String {
            val input = socket.getInputStream()
            val header = ByteArray(4)
            if (input.readNBytes(header, 0, 4) < 4) return ""
            val topicLen = readShortLe(input)
            val topic = ByteArray(topicLen)
            input.readNBytes(topic, 0, topicLen)
            val bodyLen = readIntLe(input)
            val body = ByteArray(bodyLen)
            if (bodyLen > 0) input.readNBytes(body, 0, bodyLen)
            return String(body, Charsets.UTF_8)
        }

        private fun readShortLe(input: java.io.InputStream): Int {
            val b = ByteArray(2)
            input.readNBytes(b, 0, 2)
            return (b[0].toInt() and 0xFF) or ((b[1].toInt() and 0xFF) shl 8)
        }

        private fun readIntLe(input: java.io.InputStream): Int {
            val b = ByteArray(4)
            input.readNBytes(b, 0, 4)
            return (b[0].toInt() and 0xFF) or
                ((b[1].toInt() and 0xFF) shl 8) or
                ((b[2].toInt() and 0xFF) shl 16) or
                ((b[3].toInt() and 0xFF) shl 24)
        }

        fun parseFileJson(json: String): List<MediaFile> {
            val objects = Regex("\\{[^{}]*\\}").findAll(json)
            return objects.mapNotNull { obj ->
                val text = obj.value
                val name = Regex(""""(?:name|f|file)"\s*:\s*"([^"]+)"""").find(text)?.groupValues?.get(1)
                    ?: return@mapNotNull null
                val path = Regex(""""(?:path|p)"\s*:\s*"([^"]+)"""").find(text)?.groupValues?.get(1) ?: name
                val folder = Regex(""""(?:folder|dir)"\s*:\s*"([^"]+)"""").find(text)?.groupValues?.get(1).orEmpty()
                val event = folder.contains("event", true) || name.contains("EMR", true) || name.contains("LOCK", true)
                val photo = name.endsWith(".jpg", true) || name.endsWith(".jpeg", true)
                val kind = when {
                    photo -> MediaKind.PHOTO
                    event -> MediaKind.EVENT
                    else -> MediaKind.NORMAL
                }
                MediaFile(name.substringAfterLast('/'), path, path, null, 0, "", kind, cameraFromName(name))
            }.toList()
        }
    }
}

private fun String.lineValue(key: String): String? =
    lineSequence().firstOrNull { it.contains(key, true) }?.substringAfter("=")?.trim()

class RecordingTransport(
    private val handler: (String) -> String = { defaultOk(it) },
) : HttpTransport {
    val urls = mutableListOf<String>()
    override fun getText(url: String): String {
        urls += url
        return handler(url)
    }

    override fun getBytes(url: String, rangeFrom: Long?): HttpBody {
        urls += url
        val text = handler(url)
        return HttpBody(200, text.toByteArray(), text.length.toLong())
    }

    companion object {
        fun defaultOk(url: String): String = when {
            url.contains("cmd=3015") -> SAMPLE_FILE_LIST
            url.contains("cmd=3014") -> SAMPLE_SETTINGS
            url.contains("cmd=3012") -> """<?xml version="1.0" encoding="UTF-8"?><Function><Cmd>3012</Cmd><Status>0</Status><String>NT96660@QX302</String></Function>"""
            url.contains("cmd=2016") -> """<Function><Cmd>2016</Cmd><Status>0</Status><Value>1</Value></Function>"""
            url.contains("cmd=3024") -> """<Function><Cmd>3024</Cmd><Status>0</Status><Value>1</Value></Function>"""
            url.contains("cmd=3017") -> """<Function><Cmd>3017</Cmd><Status>0</Status><Value>1073741824</Value></Function>"""
            url.contains("cmd=3029") -> """<Function><Cmd>3029</Cmd><Status>0</Status><SSID>CARDV_A1</SSID><PASSPHRASE>12345678</PASSPHRASE></Function>"""
            url.contains("record=") || url.contains("status.record") -> "0\nOK\nrecord=Recording\n"
            url.contains("cammenu") -> "<menu>FW_MSTAR_1.0</menu>"
            else -> """<Function><Cmd>0</Cmd><Status>0</Status></Function>"""
        }

        val SAMPLE_FILE_LIST = """
            <?xml version="1.0" encoding="UTF-8"?>
            <LIST>
              <File>
                <NAME>FILE20240101_120000_000.MOV</NAME>
                <FPATH>A:\CARDV\MOVIE\FILE20240101_120000_000.MOV</FPATH>
                <SIZE>1048576</SIZE>
                <TIME>2024/01/01 12:00:00</TIME>
                <ATTR>32</ATTR>
              </File>
              <File>
                <NAME>FILE20240101_120100_000.MOV</NAME>
                <FPATH>A:\CARDV\MOVIE\FILE20240101_120100_000.MOV</FPATH>
                <SIZE>2097152</SIZE>
                <TIME>2024/01/01 12:01:00</TIME>
                <ATTR>33</ATTR>
              </File>
              <File>
                <NAME>IMG20240101_120200_000.JPG</NAME>
                <FPATH>A:\CARDV\PHOTO\IMG20240101_120200_000.JPG</FPATH>
                <SIZE>512000</SIZE>
                <TIME>2024/01/01 12:02:00</TIME>
                <ATTR>32</ATTR>
              </File>
            </LIST>
        """.trimIndent()

        val SAMPLE_SETTINGS = """
            <Function>
              <Cmd>2002</Cmd><Status>0</Status>
              <Cmd>2007</Cmd><Status>1</Status>
              <Cmd>2011</Cmd><Status>2</Status>
            </Function>
        """.trimIndent()
    }
}

object ProtocolDetector {
    val defaultHosts = listOf(
        "192.168.1.254",
        "192.72.1.1",
        "192.168.11.1",
        "192.168.1.1",
        "192.168.63.9",
        "192.168.201.1",
        "192.168.8.1",
    )

    val ssidHints = listOf("dvr", "mini", "papago", "mxsx", "mtsx", "teye", "wjc", "cardv", "wykj", "lld", "decai")

    fun looksLikeDashcam(ssid: String?): Boolean {
        if (ssid.isNullOrBlank()) return false
        val lower = ssid.trim('"').lowercase()
        return ssidHints.any { lower.contains(it) }
    }

    fun detect(host: String, http: HttpTransport = OkHttpTransport()): DashcamClient? {
        val candidates = listOf(
            NovatekClient(host, http),
            MstarClient(host, http),
            HisiliconClient(host, http),
            GokeClient(host, http),
            GrainClient(host, http),
        )
        return candidates.firstOrNull { runCatching { it.probe() }.getOrDefault(false) }
    }
}

@Suppress("unused")
private val utf8: Charset = Charsets.UTF_8
