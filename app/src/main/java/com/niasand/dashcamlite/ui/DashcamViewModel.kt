package com.niasand.dashcamlite.ui

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.niasand.dashcamlite.net.WifiBinder
import com.niasand.dashcamlite.protocol.MediaFile
import com.niasand.dashcamlite.protocol.MediaKind
import com.niasand.dashcamlite.protocol.ProtocolDetector
import com.niasand.dashcamlite.protocol.SettingItem
import com.niasand.dashcamlite.service.ConnectionService
import com.niasand.dashcamlite.session.DashcamRuntime
import com.niasand.dashcamlite.session.GalleryStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class DownloadRow(val name: String, val progress: Float, val done: Boolean, val error: String?)

data class UiState(
    val connected: Boolean = false,
    val status: String = "未连接。请先把手机连到记录仪热点，再点连接。",
    val vendor: String = "",
    val firmware: String = "",
    val sd: String = "",
    val recording: Boolean = false,
    val host: String = "192.168.1.254",
    val ssid: String = "",
    val password: String = "12345678",
    val kind: MediaKind = MediaKind.NORMAL,
    val files: List<MediaFile> = emptyList(),
    val selected: Set<String> = emptySet(),
    val downloads: List<DownloadRow> = emptyList(),
    val settings: List<SettingItem> = emptyList(),
    val busy: Boolean = false,
    val liveUrl: String = "",
)

class DashcamViewModel : ViewModel() {
    var state by mutableStateOf(UiState())
        private set
    private lateinit var wifi: WifiBinder
    private lateinit var gallery: GalleryStore
    private lateinit var app: Context

    fun attach(context: Context) {
        if (this::app.isInitialized) return
        app = context.applicationContext
        wifi = WifiBinder(app)
        gallery = GalleryStore(app)
    }

    fun onPermissions(result: Map<String, Boolean>) {
        val denied = result.filterValues { !it }.keys
        if (denied.isNotEmpty()) {
            state = state.copy(status = "部分权限未授予，仍可在已连接热点后尝试绑定网络。")
        }
    }

    fun updateHost(value: String) { state = state.copy(host = value) }
    fun updateSsid(value: String) { state = state.copy(ssid = value) }
    fun updatePassword(value: String) { state = state.copy(password = value) }

    fun connect(useSpecifier: Boolean) {
        state = state.copy(busy = true, status = "正在绑定记录仪网络…")
        val ready: (String?) -> Unit = { gateway ->
            val host = state.host.ifBlank { gateway ?: "192.168.1.254" }
            handshake(host)
        }
        val lost: (String) -> Unit = { message ->
            state = state.copy(busy = false, connected = false, status = message)
        }
        if (useSpecifier && state.ssid.isNotBlank()) {
            wifi.requestSsid(state.ssid, state.password, ready, lost)
        } else {
            wifi.bindExistingWifi(ready, lost)
        }
    }

    private fun handshake(host: String) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { DashcamRuntime.connect(host) }
            }
            result.onSuccess { info ->
                ConnectionService.start(app)
                state = state.copy(
                    busy = false,
                    connected = true,
                    status = "已连接 ${info.vendor}",
                    vendor = info.vendor,
                    firmware = info.firmware,
                    sd = "${info.sdStatusText}${info.freeBytes?.let { " · 剩余 ${it / 1024 / 1024} MB" } ?: ""}",
                    recording = info.recording,
                    host = info.host,
                    liveUrl = DashcamRuntime.client?.liveUrl().orEmpty(),
                )
            }.onFailure {
                state = state.copy(busy = false, connected = false, status = it.message ?: "连接失败")
            }
        }
    }

    fun disconnect() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { DashcamRuntime.disconnect(sendResume = true) }
            wifi.release("user")
            ConnectionService.stop(app)
            state = UiState(host = state.host, ssid = state.ssid, password = state.password, status = "已断开，并已请求记录仪恢复录像。")
        }
    }

    fun loadFiles(kind: MediaKind) {
        if (!state.connected) {
            state = state.copy(kind = kind, files = emptyList(), status = "未连接，文件列表为空。")
            return
        }
        state = state.copy(busy = true, kind = kind)
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    DashcamRuntime.enterFiles()
                    DashcamRuntime.refreshFiles(kind)
                }
            }
            result.onSuccess { files ->
                state = state.copy(busy = false, files = files, selected = emptySet(), status = "共 ${files.size} 个文件")
            }.onFailure {
                state = state.copy(busy = false, status = it.message ?: "读取文件失败")
            }
        }
    }

    fun toggle(file: MediaFile) {
        val next = state.selected.toMutableSet()
        if (!next.add(file.url)) next.remove(file.url)
        state = state.copy(selected = next)
    }

    fun downloadSelected() {
        val chosen = state.files.filter { it.url in state.selected }
        if (chosen.isEmpty()) return
        viewModelScope.launch {
            val rows = chosen.map { DownloadRow(it.name, 0f, false, null) }.toMutableList()
            state = state.copy(downloads = rows, busy = true)
            chosen.forEachIndexed { index, file ->
                val result = withContext(Dispatchers.IO) {
                    runCatching {
                        gallery.downloadToGallery(file) { done, total ->
                            val progress = if (total <= 0) 0f else done.toFloat() / total
                            rows[index] = rows[index].copy(progress = progress)
                        }
                    }
                }
                rows[index] = if (result.isSuccess) {
                    rows[index].copy(progress = 1f, done = true)
                } else {
                    rows[index].copy(error = result.exceptionOrNull()?.message ?: "失败，可重试")
                }
                state = state.copy(downloads = rows.toList())
            }
            state = state.copy(busy = false, status = "下载结束。成功的文件在相册 DashcamLite 目录。")
        }
    }

    fun deleteSelected() {
        val chosen = state.files.filter { it.url in state.selected }
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                chosen.forEach { runCatching { DashcamRuntime.client?.delete(it) } }
            }
            loadFiles(state.kind)
        }
    }

    fun toggleRecord() {
        viewModelScope.launch {
            val start = !state.recording
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    if (start) DashcamRuntime.client?.startRecord() else DashcamRuntime.client?.stopRecord()
                }
            }
            state = if (result.isSuccess) {
                state.copy(recording = start, status = if (start) "已开始录像" else "已停止录像")
            } else state.copy(status = result.exceptionOrNull()?.message ?: "录像指令失败")
        }
    }

    fun syncTime() {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { DashcamRuntime.client?.syncTime() } }
            state = state.copy(status = if (result.isSuccess) "时间已同步" else "校时失败")
        }
    }

    fun formatSd() {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { DashcamRuntime.client?.formatSd() } }
            state = state.copy(status = if (result.isSuccess) "已发送格式化指令" else "格式化失败")
        }
    }

    fun loadSettings() {
        if (!state.connected) return
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { DashcamRuntime.readSettings() } }
            result.onSuccess { state = state.copy(settings = it) }
        }
    }

    fun writeSetting(item: SettingItem, optionId: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { runCatching { DashcamRuntime.client?.writeSetting(item, optionId) } }
            loadSettings()
        }
    }

    fun switchCamera() {
        viewModelScope.launch {
            val side = withContext(Dispatchers.IO) { runCatching { DashcamRuntime.client?.switchCamera() }.getOrNull() }
            state = state.copy(status = "镜头：${side ?: "未连接"}", liveUrl = DashcamRuntime.client?.liveUrl().orEmpty())
        }
    }

    fun hintForSsid(ssid: String): String = when {
        ssid.isBlank() -> "常见热点名包含 CARDV、DVR、Papago"
        ProtocolDetector.looksLikeDashcam(ssid) -> "这个名称很像记录仪热点"
        else -> "名称不像常见记录仪热点，仍可以手动填写 IP 连接"
    }
}
