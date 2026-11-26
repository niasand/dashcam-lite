package com.niasand.dashcamlite.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import com.niasand.dashcamlite.net.DashcamLink
import com.niasand.dashcamlite.protocol.MediaFile
import com.niasand.dashcamlite.protocol.MediaKind
import com.niasand.dashcamlite.protocol.shouldDecodeAsImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.SequenceInputStream

private enum class Tab(val label: String) { Connect("连接"), Preview("预览"), Files("文件"), Settings("设置") }

@Composable
fun DashcamLiteApp(model: DashcamViewModel) {
    val state = model.state
    var tab by remember { mutableStateOf(Tab.Connect) }
    MaterialTheme(colorScheme = darkColorScheme(
        primary = Color(0xFFF2B544),
        background = Color(0xFF102033),
        surface = Color(0xFF17324C),
    )) {
        Scaffold(
            containerColor = Color(0xFF102033),
            bottomBar = {
                NavigationBar {
                    Tab.entries.forEach { item ->
                        NavigationBarItem(
                            selected = tab == item,
                            onClick = {
                                tab = item
                                if (item == Tab.Files) model.loadFiles(state.kind)
                                if (item == Tab.Preview) model.openPreview()
                                if (item == Tab.Settings) model.loadSettings()
                            },
                            icon = {
                                Icon(
                                    when (item) {
                                        Tab.Connect -> Icons.Default.Wifi
                                        Tab.Preview -> Icons.Default.Videocam
                                        Tab.Files -> Icons.Default.VideoLibrary
                                        Tab.Settings -> Icons.Default.Settings
                                    },
                                    contentDescription = item.label,
                                )
                            },
                            label = { Text(item.label) },
                        )
                    }
                }
            },
        ) { padding ->
            when (tab) {
                Tab.Connect -> ConnectScreen(state, model, padding)
                Tab.Preview -> PreviewScreen(state, model, padding)
                Tab.Files -> FilesScreen(state, model, padding)
                Tab.Settings -> SettingsScreen(state, model, padding)
            }
        }
    }
}

@Composable
private fun ConnectScreen(state: UiState, model: DashcamViewModel, padding: PaddingValues) {
    Column(
        Modifier.fillMaxSize().padding(padding).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("行车记录仪Lite", style = MaterialTheme.typography.headlineMedium, color = Color.White)
        Text(state.status, color = Color(0xFFD5E4F2))
        OutlinedTextField(state.host, model::updateHost, label = { Text("记录仪 IP") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(state.ssid, model::updateSsid, label = { Text("热点名称（可选）") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(state.password, model::updatePassword, label = { Text("热点密码") }, modifier = Modifier.fillMaxWidth())
        Text(model.hintForSsid(state.ssid), color = Color(0xFF9BB4C8))
        Button(onClick = { model.connect(useSpecifier = false) }, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
            Text(if (state.connected) "重新绑定并连接" else "绑定当前 Wi-Fi 并连接")
        }
        OutlinedButton(onClick = { model.connect(useSpecifier = true) }, enabled = !state.busy && state.ssid.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
            Text("让系统加入上面的热点")
        }
        if (state.connected) {
            OutlinedButton(onClick = model::disconnect, modifier = Modifier.fillMaxWidth()) { Text("断开并恢复录像") }
            InfoCard(state)
        } else {
            Text("当前未连接。连接成功后可以预览、下载照片和视频。", color = Color(0xFF9BB4C8))
        }
    }
}

@Composable
private fun InfoCard(state: UiState) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("芯片协议：${state.vendor}")
            Text("固件：${state.firmware}")
            Text("存储卡：${state.sd}")
            Text(if (state.recording) "录像中" else "未在录像")
        }
    }
}

@Composable
private fun PreviewScreen(state: UiState, model: DashcamViewModel, padding: PaddingValues) {
    Column(Modifier.fillMaxSize().padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(if (state.connected) "实时预览" else "预览（未连接）", color = Color.White, style = MaterialTheme.typography.titleLarge)
        Text(
            state.liveUrl.ifBlank { "连接后会依次尝试 RTSP 和 MJPEG" },
            color = Color(0xFF9BB4C8),
        )
        if (state.previewError != null) {
            Text(state.previewError, color = Color(0xFFFFB4A8))
        }
        if (state.connected && state.liveUrl.isNotBlank() && state.previewError == null) {
            CameraPicture(
                url = state.liveUrl,
                onError = model::previewFailed,
                modifier = Modifier.fillMaxWidth().weight(1f),
            )
        } else {
            Text(
                if (state.connected) "正在准备画面…" else "未连接时这里是黑的，因为还没有记录仪画面。",
                color = Color(0xFF9BB4C8),
                modifier = Modifier.weight(1f),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = model::toggleRecord, enabled = state.connected) {
                Text(if (state.recording) "停止录像" else "开始录像")
            }
            OutlinedButton(onClick = model::openPreview, enabled = state.connected) { Text("重试画面") }
        }
        Text("画面走记录仪热点本身，不经过手机的移动网络。离开本页会恢复录像。", color = Color(0xFF9BB4C8))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FilesScreen(state: UiState, model: DashcamViewModel, padding: PaddingValues) {
    Column(Modifier.fillMaxSize().padding(padding).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(if (state.connected) "记录仪文件" else "文件（未连接）", color = Color.White, style = MaterialTheme.typography.titleLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(MediaKind.NORMAL to "视频", MediaKind.EVENT to "紧急", MediaKind.PHOTO to "照片").forEach { (kind, label) ->
                FilterChip(selected = state.kind == kind, onClick = { model.loadFiles(kind) }, label = { Text(label) })
            }
        }
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            SegmentedButton(
                selected = state.fileViewMode == FileViewMode.PREVIEW,
                onClick = { model.setFileViewMode(FileViewMode.PREVIEW) },
                shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
            ) { Text("预览") }
            SegmentedButton(
                selected = state.fileViewMode == FileViewMode.LIST,
                onClick = { model.setFileViewMode(FileViewMode.LIST) },
                shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
            ) { Text("列表") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = model::downloadSelected, enabled = state.selected.isNotEmpty()) { Text("下载") }
            OutlinedButton(onClick = model::deleteSelected, enabled = state.selected.isNotEmpty()) { Text("删除") }
        }
        state.downloads.forEach { row ->
            Column {
                Text("${row.name} ${row.error ?: if (row.done) "完成" else ""}", color = Color.White)
                LinearProgressIndicator(progress = { row.progress }, modifier = Modifier.fillMaxWidth())
            }
        }
        if (state.files.isEmpty()) {
            Text("这里还没有文件。连接记录仪后可以选择视频、紧急或照片。", color = Color(0xFF9BB4C8), modifier = Modifier.padding(top = 12.dp))
        } else if (state.fileViewMode == FileViewMode.PREVIEW) {
            FilePreviewGrid(state, model, Modifier.weight(1f))
        } else {
            FileNameList(state, model, Modifier.weight(1f))
        }
        state.openFile?.let { file -> FilePreviewDialog(file) { model.showFile(null) } }
    }
}

@Composable
private fun FilePreviewGrid(state: UiState, model: DashcamViewModel, modifier: Modifier) {
    LazyVerticalGrid(columns = GridCells.Fixed(2), modifier = modifier, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(state.files, key = { it.url }) { file ->
            Column(Modifier.clickable { model.showFile(file) }) {
                BoxWithCheckbox(file, model) {
                    FileThumb(file.thumbUrl ?: file.url, edge = 120)
                }
                Text(file.name, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(fileMeta(file), color = Color(0xFF9BB4C8), maxLines = 2)
                DownloadNote(state, file.name)
            }
        }
    }
}

@Composable
private fun FileNameList(state: UiState, model: DashcamViewModel, modifier: Modifier) {
    LazyColumn(modifier) {
        items(state.files, key = { it.url }) { file ->
            Row(
                Modifier.fillMaxWidth().padding(vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(checked = file.url in state.selected, onCheckedChange = { model.toggle(file) })
                Column(Modifier.weight(1f).clickable { model.showFile(file) }) {
                    Text(file.name, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(fileMeta(file), color = Color(0xFF9BB4C8))
                    DownloadNote(state, file.name)
                }
            }
        }
    }
}

@Composable
private fun BoxWithCheckbox(file: MediaFile, model: DashcamViewModel, thumb: @Composable () -> Unit) {
    androidx.compose.foundation.layout.Box(Modifier.fillMaxWidth().height(120.dp)) {
        thumb()
        Checkbox(
            checked = stateSelected(file, model),
            onCheckedChange = { model.toggle(file) },
            modifier = Modifier.align(Alignment.TopStart),
        )
    }
}

private fun stateSelected(file: MediaFile, model: DashcamViewModel): Boolean = file.url in model.state.selected

@Composable
private fun DownloadNote(state: UiState, name: String) {
    val row = state.downloads.lastOrNull { it.name == name } ?: return
    val line = row.error ?: if (row.done) "已保存" else return
    Text(line, color = if (row.error != null) Color(0xFFFFB4A8) else Color(0xFF9BB4C8), maxLines = 3)
}

private fun fileMeta(file: MediaFile): String {
    val size = if (file.size >= 1024 * 1024) {
        "%.1f MB".format(file.size / (1024.0 * 1024.0))
    } else {
        "${file.size / 1024} KB"
    }
    return listOf(file.time, size).filter { it.isNotBlank() }.joinToString(" · ")
}

@Composable
private fun FileThumb(url: String, edge: Int = 64) {
    var bitmap by remember(url) { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
    var error by remember(url) { mutableStateOf<String?>(null) }
    LaunchedEffect(url) {
        val loaded = withContext(Dispatchers.IO) {
            runCatching { decodeBoundedImage(url) }
        }
        loaded.onSuccess { bitmap = it }.onFailure { error = it.message }
    }
    when {
        bitmap != null -> Image(bitmap!!, contentDescription = null, modifier = Modifier.size(edge.dp))
        error != null && edge > 64 -> Text("预览失败：$error", color = Color(0xFFFFB4A8))
    }
}

private fun decodeBoundedImage(url: String): androidx.compose.ui.graphics.ImageBitmap {
    DashcamLink.client().newCall(
        okhttp3.Request.Builder().url(url).header("Connection", "close").build(),
    ).execute().use { response ->
        if (response.code !in 200..299) error("HTTP ${response.code}")
        val body = response.body ?: error("没有图片数据")
        val length = body.contentLength().takeIf { it >= 0 }
        body.byteStream().use { raw ->
            val magic = ByteArray(8)
            val read = raw.read(magic)
            val sniffed = if (read > 0) magic.copyOf(read) else ByteArray(0)
            if (!shouldDecodeAsImage(length, sniffed)) error("不是图片，已跳过以免占满内存")
            val stream = SequenceInputStream(ByteArrayInputStream(sniffed), raw)
            val options = BitmapFactory.Options().apply {
                inSampleSize = 4
                inPreferredConfig = android.graphics.Bitmap.Config.RGB_565
            }
            return BitmapFactory.decodeStream(stream, null, options)?.asImageBitmap()
                ?: error("图片无法解码")
        }
    }
}

@Composable
private fun FilePreviewDialog(file: MediaFile, onDismiss: () -> Unit) {
    var playError by remember(file.url) { mutableStateOf<String?>(null) }
    val photo = file.name.endsWith(".jpg", true) || file.name.endsWith(".jpeg", true)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(file.name) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(file.url.substringAfter("://").substringBefore("/"), color = Color(0xFF9BB4C8))
                if (photo) {
                    FileThumb(file.url, edge = 220)
                } else {
                    CameraPicture(
                        url = file.url,
                        onError = { playError = it },
                        modifier = Modifier.fillMaxWidth().size(220.dp),
                    )
                    if (playError != null) Text("播放失败：$playError", color = Color(0xFFFFB4A8))
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}

@Composable
private fun SettingsScreen(state: UiState, model: DashcamViewModel, padding: PaddingValues) {
    var confirmFormat by remember { mutableStateOf(false) }
    LazyColumn(Modifier.fillMaxSize().padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Text("设备与设置", color = Color.White, style = MaterialTheme.typography.titleLarge) }
        item { InfoCard(state) }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = model::syncTime, enabled = state.connected) { Text("同步时间") }
                OutlinedButton(onClick = { confirmFormat = true }, enabled = state.connected) { Text("格式化存储卡") }
            }
        }
        items(state.settings) { item ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text("${item.label}：${item.options.firstOrNull { it.id == item.value }?.label ?: item.value}")
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        item.options.take(4).forEach { option ->
                            TextButton(onClick = { model.writeSetting(item, option.id) }) { Text(option.label) }
                        }
                    }
                }
            }
        }
        item {
            Text("关于", color = Color.White, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
            Text("只保留连接记录仪、预览、回放下载和设备设置。没有广告、账号、论坛和推送。", color = Color(0xFF9BB4C8))
            Text("© 2026 版权所有 yangzhiwei.727", color = Color(0xFFF2B544), modifier = Modifier.padding(top = 8.dp))
        }
    }
    if (confirmFormat) {
        AlertDialog(
            onDismissRequest = { confirmFormat = false },
            title = { Text("格式化存储卡") },
            text = { Text("这会清空记录仪存储卡上的录像和照片。确定继续？") },
            confirmButton = {
                TextButton(onClick = {
                    confirmFormat = false
                    model.formatSd()
                }) { Text("格式化") }
            },
            dismissButton = { TextButton(onClick = { confirmFormat = false }) { Text("取消") } },
        )
    }
}
