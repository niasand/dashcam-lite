package com.niasand.dashcamlite.protocol

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 联咏 NT966xx Wi-Fi CGI。
 * 路径形态来自 com.tima.lib.dr.res.devices.novatek.device.beans.DeviceRequest
 * 与 com.tima.app.dr.novatek.helper.DrCallbackImpl。
 */
class NovatekClient(
    override val host: String,
    private val http: HttpTransport,
) : DashcamClient {
    override val vendor: String = "novatek"
    override val commands = mutableListOf<CommandTrace>()
    var preferRtsp: Boolean = true
    private var cameraIndex = 0

    fun url(cmd: Int, par: String? = null, str: String? = null): String {
        val sb = StringBuilder("http://$host/?custom=1&cmd=$cmd")
        if (par != null) sb.append("&par=").append(par)
        if (str != null) sb.append("&str=").append(str)
        return sb.toString()
    }

    private fun get(cmd: Int, par: String? = null, str: String? = null): String {
        val target = url(cmd, par, str)
        commands += CommandTrace("GET", target)
        return http.getText(target)
    }

    override fun probe(): Boolean = runCatching {
        val xml = get(CMD_VERSION)
        val status = XmlSnips.first(xml, "Status")?.toIntOrNull()
        status == 0 || XmlSnips.first(xml, "Cmd") == CMD_VERSION.toString()
    }.getOrDefault(false)

    override fun handshake(): DeviceInfo {
        val recordXml = get(CMD_RECORD_STATUS)
        val recording = XmlSnips.first(recordXml, "Value")?.toIntOrNull()?.let { it > 0 } ?: false
        val fwXml = get(CMD_VERSION)
        var firmware = XmlSnips.first(fwXml, "String").orEmpty()
        var model: String? = null
        if (firmware.contains("@")) {
            val parts = firmware.split("@")
            firmware = parts[0]
            model = parts.getOrNull(1)
        }
        syncTime()
        val wifi = runCatching { get(CMD_WIFI_INFO) }.getOrNull()
        val ssid = wifi?.let { XmlSnips.first(it, "SSID") }
        val sdXml = runCatching { get(CMD_SD_STATUS) }.getOrNull()
        val sdValue = sdXml?.let { XmlSnips.first(it, "Value")?.toIntOrNull() }
        val free = runCatching {
            XmlSnips.first(get(CMD_FREE_SPACE), "Value")?.toLongOrNull()
        }.getOrNull()
        runCatching { get(CMD_ALL_SETTINGS) }
        return DeviceInfo(
            vendor = vendor,
            host = host,
            firmware = firmware.ifBlank { "未知" },
            recording = recording,
            sdReady = sdValue == 1,
            sdStatusText = sdLabel(sdValue),
            freeBytes = free,
            ssid = ssid,
            model = model,
        )
    }

    override fun heartbeat() {
        get(CMD_HEARTBEAT)
    }

    override fun listFiles(kind: MediaKind): List<MediaFile> {
        val xml = get(CMD_FILE_LIST)
        return parseFileList(xml, host).filter { it.kind == kind || (kind == MediaKind.PARK && it.kind == MediaKind.EVENT) }
    }

    override fun delete(file: MediaFile) {
        get(CMD_DELETE, str = file.remotePath)
    }

    override fun liveUrl(): String = if (preferRtsp) {
        "rtsp://$host/xxxx.mov"
    } else {
        "http://$host:8192"
    }

    override fun enterPlayback() {
        get(CMD_MODE, par = MODE_PLAYBACK)
    }

    override fun startRecord() {
        get(CMD_MODE, par = MODE_MOVIE)
        get(CMD_RECORD, par = "1")
    }

    override fun stopRecord() {
        get(CMD_MODE, par = MODE_MOVIE)
        get(CMD_RECORD, par = "0")
    }

    override fun resumeRecording() {
        // PlayActivity.onPause / onDestroy：notifyDvrAppExit 后 startRecord
        // 即 DrCallbackImpl.startRecord → switchModeMovie(3001,par=1) 再 startRecord(2001,par=1)
        startRecord()
    }

    override fun syncTime(now: Date) {
        val date = SimpleDateFormat("yyyy-MM-dd", Locale.ENGLISH).format(now)
        val time = SimpleDateFormat("HH:mm:ss", Locale.ENGLISH).format(now)
        get(CMD_SET_DATE, str = date)
        get(CMD_SET_TIME, str = time)
    }

    override fun formatSd() {
        get(CMD_FORMAT)
    }

    override fun readSettings(): List<SettingItem> {
        val xml = get(CMD_ALL_SETTINGS)
        val cmds = XmlSnips.texts(xml, "Cmd")
        val values = XmlSnips.texts(xml, "Status")
        val map = cmds.zip(values).toMap()
        return SETTING_DEFS.mapNotNull { def ->
            val current = map[def.cmd.toString()] ?: return@mapNotNull null
            SettingItem(def.cmd.toString(), def.label, current, def.options)
        }
    }

    override fun writeSetting(item: SettingItem, optionId: String) {
        get(item.id.toInt(), par = optionId)
    }

    override fun switchCamera(): String {
        cameraIndex = (cameraIndex + 1) % 6
        get(CMD_SWITCH_CAMERA, par = cameraIndex.toString())
        return if (cameraIndex == 0) "front" else "camera-$cameraIndex"
    }

    companion object {
        const val CMD_CAPTURE = 1001
        const val CMD_PIC_RESOLUTION = 1002
        const val CMD_RECORD = 2001
        const val CMD_VIDEO_RESOLUTION = 2002
        const val CMD_LOOPING = 2003
        const val CMD_WDR = 2004
        const val CMD_MOTION = 2006
        const val CMD_SOUND = 2007
        const val CMD_WATERMARK = 2008
        const val CMD_GSENSOR = 2011
        const val CMD_RECORD_MODE = 2012
        const val CMD_RECORD_STATUS = 2016
        const val CMD_MODE = 3001
        const val CMD_SET_WIFI_NAME = 3003
        const val CMD_SET_WIFI_PW = 3004
        const val CMD_SET_DATE = 3005
        const val CMD_SET_TIME = 3006
        const val CMD_FORMAT = 3010
        const val CMD_VERSION = 3012
        const val CMD_ALL_SETTINGS = 3014
        const val CMD_FILE_LIST = 3015
        const val CMD_HEARTBEAT = 3016
        const val CMD_FREE_SPACE = 3017
        const val CMD_SD_STATUS = 3024
        const val CMD_SWITCH_CAMERA = 3028
        const val CMD_WIFI_INFO = 3029
        const val CMD_DELETE = 4003
        const val CMD_THUMB = 4001
        const val MODE_PHOTO = "0"
        const val MODE_MOVIE = "1"
        const val MODE_PLAYBACK = "2"
        const val NOTIFY_PORT = 3333

        val SETTING_DEFS = listOf(
            SettingDef(CMD_VIDEO_RESOLUTION, "录像分辨率", listOf(
                SettingOption("0", "FULL HD"),
                SettingOption("1", "720P"),
                SettingOption("2", "WVGA"),
                SettingOption("3", "VGA"),
            )),
            SettingDef(CMD_PIC_RESOLUTION, "照片分辨率", listOf(
                SettingOption("0", "12M"),
                SettingOption("1", "10M"),
                SettingOption("2", "8M"),
                SettingOption("3", "5M"),
                SettingOption("4", "3M"),
                SettingOption("5", "2M"),
            )),
            SettingDef(CMD_GSENSOR, "碰撞灵敏度", listOf(
                SettingOption("1", "低"),
                SettingOption("2", "中"),
                SettingOption("3", "高"),
                SettingOption("0", "关闭"),
            )),
            SettingDef(CMD_LOOPING, "循环录像", listOf(
                SettingOption("1", "3分钟"),
                SettingOption("2", "5分钟"),
                SettingOption("3", "10分钟"),
                SettingOption("0", "关闭"),
            )),
            SettingDef(CMD_SOUND, "录音", onOff()),
            SettingDef(CMD_WATERMARK, "时间水印", onOff()),
            SettingDef(CMD_MOTION, "移动侦测", onOff()),
            SettingDef(CMD_WDR, "宽动态", onOff()),
            SettingDef(CMD_RECORD_MODE, "录像模式", onOff()),
        )

        private fun onOff() = listOf(SettingOption("0", "关"), SettingOption("1", "开"))

        fun parseFileList(xml: String, host: String): List<MediaFile> {
            val files = Regex("<File\\b[^>]*>([\\s\\S]*?)</File>", RegexOption.IGNORE_CASE)
                .findAll(xml)
            return files.map { block ->
                val body = block.groupValues[1]
                val path = XmlSnips.first(body, "FPATH").orEmpty()
                val name = XmlSnips.first(body, "NAME") ?: path.substringAfterLast('\\').substringAfterLast('/')
                val attr = XmlSnips.first(body, "ATTR")?.toIntOrNull() ?: 0
                val size = XmlSnips.first(body, "SIZE")?.toLongOrNull() ?: 0L
                val time = XmlSnips.first(body, "TIME").orEmpty()
                val normalized = path.replace('\\', '/')
                val slash = normalized.indexOf('/')
                val urlPath = if (slash >= 0) normalized.substring(slash) else "/$normalized"
                val url = "http://$host$urlPath"
                val picture = name.uppercase().let { it.endsWith("JPG") || it.endsWith("JPEG") }
                val event = (attr and 1) == 1
                val kind = when {
                    picture -> MediaKind.PHOTO
                    event -> MediaKind.EVENT
                    else -> MediaKind.NORMAL
                }
                MediaFile(
                    name = name,
                    remotePath = path,
                    url = url,
                    thumbUrl = "$url?custom=1&cmd=$CMD_THUMB",
                    size = size,
                    time = time,
                    kind = kind,
                    camera = cameraFromName(name),
                )
            }.toList()
        }

        fun sdLabel(value: Int?): String = when (value) {
            null -> "未知"
            0 -> "未插入"
            1 -> "正常"
            2 -> "需要格式化"
            else -> "状态 $value"
        }
    }
}

data class SettingDef(val cmd: Int, val label: String, val options: List<SettingOption>)
