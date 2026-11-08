package com.niasand.dashcamlite.ui

import android.media.MediaPlayer
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.niasand.dashcamlite.protocol.MediaKind

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
        Text(state.liveUrl.ifBlank { "连接后会显示 RTSP 或 MJPEG 地址" }, color = Color(0xFF9BB4C8))
        AndroidView(
            factory = { context ->
                SurfaceView(context).apply {
                    holder.addCallback(object : SurfaceHolder.Callback {
                        var player: MediaPlayer? = null
                        override fun surfaceCreated(holder: SurfaceHolder) {
                            if (state.liveUrl.isBlank()) return
                            player = MediaPlayer().apply {
                                setDisplay(holder)
                                setDataSource(state.liveUrl)
                                setOnPreparedListener { it.start() }
                                prepareAsync()
                            }
                        }
                        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}
                        override fun surfaceDestroyed(holder: SurfaceHolder) {
                            player?.release()
                            player = null
                        }
                    })
                }
            },
            modifier = Modifier.fillMaxWidth().weight(1f),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = model::toggleRecord, enabled = state.connected) {
                Text(if (state.recording) "停止录像" else "开始录像")
            }
            OutlinedButton(onClick = model::switchCamera, enabled = state.connected) { Text("切换镜头") }
        }
        Text("离开此页或把应用退到后台时，会发送恢复录像指令。", color = Color(0xFF9BB4C8))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FilesScreen(state: UiState, model: DashcamViewModel, padding: PaddingValues) {
    Column(Modifier.fillMaxSize().padding(padding).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(if (state.connected) "记录仪文件" else "文件（未连接）", color = Color.White, style = MaterialTheme.typography.titleLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(MediaKind.NORMAL to "普通", MediaKind.EVENT to "紧急", MediaKind.PHOTO to "照片").forEach { (kind, label) ->
                FilterChip(selected = state.kind == kind, onClick = { model.loadFiles(kind) }, label = { Text(label) })
            }
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
            Text("这里还没有文件。连接记录仪后可以选择普通、紧急或照片。", color = Color(0xFF9BB4C8), modifier = Modifier.padding(top = 12.dp))
        }
        LazyColumn(Modifier.weight(1f)) {
            items(state.files, key = { it.url }) { file ->
                Row(
                    Modifier.fillMaxWidth().toggleable(file.url in state.selected) { model.toggle(file) }.padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Checkbox(checked = file.url in state.selected, onCheckedChange = { model.toggle(file) })
                    Column {
                        Text(file.name, color = Color.White)
                        Text("${file.time} · ${file.size / 1024} KB", color = Color(0xFF9BB4C8))
                    }
                }
            }
        }
    }
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
