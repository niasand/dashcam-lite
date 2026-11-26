package com.niasand.dashcamlite.session

import com.niasand.dashcamlite.net.DashcamLink
import com.niasand.dashcamlite.protocol.DownloadUrls
import com.niasand.dashcamlite.protocol.DashcamClient
import com.niasand.dashcamlite.protocol.FileDownloader
import com.niasand.dashcamlite.protocol.DeviceInfo
import com.niasand.dashcamlite.protocol.JieliClient
import com.niasand.dashcamlite.protocol.MediaFile
import com.niasand.dashcamlite.protocol.MediaKind
import com.niasand.dashcamlite.protocol.NovatekClient
import com.niasand.dashcamlite.protocol.ProtocolDetector
import com.niasand.dashcamlite.protocol.SettingItem
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * 连接会话。心跳间隔对齐 NovaHeartBeatKit 默认 5000ms。
 * 退到后台、断开、任务被划掉时调用 resumeRecording，顺序与原版
 * DrCallbackImpl.startRecord 相同：先切回录像模式，再打开录像。
 */
object DashcamRuntime {
    @Volatile var client: DashcamClient? = null
        private set
    @Volatile var info: DeviceInfo? = null
        private set
    @Volatile var message: String = "未连接"
    @Volatile var files: List<MediaFile> = emptyList()
    @Volatile var settings: List<SettingItem> = emptyList()
    @Volatile var inPlayback: Boolean = false

    private val heartbeat = AtomicBoolean(false)
    private val pauseHeartbeat = AtomicBoolean(false)
    private val heartbeatInFlight = AtomicBoolean(false)
    private var heartbeatThread: Thread? = null
    private var notifySocket: Socket? = null
    private val resumed = AtomicBoolean(false)
    private val lock = Any()

    fun connect(host: String): DeviceInfo {
        disconnect(sendResume = false)
        val http = DashcamLink.transport()
        val found = ProtocolDetector.detect(host, http)
            ?: error("在 $host 上没有识别到记录仪。请确认手机已连上记录仪热点。")
        val device = found.handshake()
        synchronized(lock) {
            client = found
            info = device
            message = "已连接 ${device.vendor} @ ${device.host}"
            resumed.set(false)
            inPlayback = false
        }
        startHeartbeat()
        if (found is NovatekClient) startNotifySocket(found.host.substringBefore(':'))
        return device
    }

    fun refreshFiles(kind: MediaKind): List<MediaFile> {
        val list = requireClient().listFiles(kind)
        files = list
        return list
    }

    /** 预览前回到录像模式，否则记录仪停在回放模式时没有实时画面。 */
    fun prepareLive() {
        val current = requireClient()
        current.resumeRecording()
        inPlayback = false
    }

    fun enterFiles() {
        val current = requireClient()
        current.enterPlayback()
        inPlayback = true
        resumed.set(false)
    }

    fun leavePlaybackAndRecord() {
        val current = client ?: return
        if (!inPlayback && current !is NovatekClient) return
        current.resumeRecording()
        inPlayback = false
        resumed.set(true)
    }

    fun ensureRecording() {
        if (pauseHeartbeat.get()) return
        val current = client ?: return
        current.resumeRecording()
        inPlayback = false
        resumed.set(true)
    }

    /**
     * 记录仪的 HTTP 服务通常同时只处理一个请求。下载前停心跳、切到回放，
     * 否则文件 GET 会被 cmd=3016 打断，或在录像模式下被拒绝。
     */
    fun <T> withQuietLink(block: () -> T): T {
        pauseHeartbeat.set(true)
        val deadline = System.currentTimeMillis() + 8_000
        while (heartbeatInFlight.get() && System.currentTimeMillis() < deadline) {
            Thread.sleep(50)
        }
        try {
            runCatching {
                client?.enterPlayback()
                inPlayback = true
            }
            return block()
        } finally {
            pauseHeartbeat.set(false)
        }
    }

    fun fetchToFile(file: MediaFile, dest: java.io.File, onProgress: (Long, Long) -> Unit): String {
        val current = requireClient()
        val urls = DownloadUrls.candidates(current.vendor, current.host, file.remotePath, file.name)
        return FileDownloader(DashcamLink.transport()).downloadFirst(urls, dest, onProgress)
    }

    fun disconnect(sendResume: Boolean) {
        stopHeartbeat()
        closeNotify()
        val current = client
        if (sendResume && current != null && !resumed.get()) {
            runCatching { current.resumeRecording() }
            resumed.set(true)
        }
        runCatching { current?.close() }
        client = null
        info = null
        files = emptyList()
        settings = emptyList()
        inPlayback = false
        message = "未连接"
    }

    /** 进程被系统回收前能跑到的最安全动作：把录像打开，再停心跳。 */
    fun resumeForProcessEnd() {
        val current = client ?: return
        stopHeartbeat()
        if (!resumed.get()) {
            runCatching { current.resumeRecording() }
            resumed.set(true)
        }
        closeNotify()
        runCatching { current.close() }
    }

    fun readSettings(): List<SettingItem> {
        val list = requireClient().readSettings()
        settings = list
        return list
    }

    private fun requireClient(): DashcamClient = client ?: error("尚未连接记录仪")

    private fun startHeartbeat() {
        heartbeat.set(true)
        heartbeatThread = thread(name = "dashcam-heartbeat", isDaemon = true) {
            while (heartbeat.get()) {
                if (!pauseHeartbeat.get()) {
                    heartbeatInFlight.set(true)
                    try {
                        runCatching { client?.heartbeat() }
                    } finally {
                        heartbeatInFlight.set(false)
                    }
                }
                try {
                    Thread.sleep(5000)
                } catch (_: InterruptedException) {
                    break
                }
            }
        }
    }

    private fun stopHeartbeat() {
        heartbeat.set(false)
        heartbeatThread?.interrupt()
        heartbeatThread = null
    }

    private fun startNotifySocket(host: String) {
        thread(name = "dashcam-notify", isDaemon = true) {
            while (heartbeat.get()) {
                try {
                    val socket = Socket()
                    socket.connect(InetSocketAddress(host, NovatekClient.NOTIFY_PORT), 5000)
                    notifySocket = socket
                    socket.soTimeout = 0
                    val reader = socket.getInputStream().bufferedReader()
                    while (heartbeat.get()) {
                        if (reader.readLine() == null) break
                    }
                } catch (_: Exception) {
                    Thread.sleep(2000)
                }
            }
        }
    }

    private fun closeNotify() {
        runCatching { notifySocket?.close() }
        notifySocket = null
    }
}
