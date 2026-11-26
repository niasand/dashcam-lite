@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.niasand.dashcamlite.ui

import android.graphics.BitmapFactory
import android.net.Network
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.PlayerView
import com.niasand.dashcamlite.net.DashcamLink
import com.niasand.dashcamlite.protocol.JpegFrames
import com.niasand.dashcamlite.protocol.LiveCatalog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

@Composable
fun CameraPicture(url: String, onError: (String) -> Unit, modifier: Modifier = Modifier) {
    if (url.isBlank()) return
    if (LiveCatalog.isMjpeg(url)) {
        MjpegPicture(url, onError, modifier)
    } else {
        StreamPicture(url, onError, modifier)
    }
}

@Composable
private fun StreamPicture(url: String, onError: (String) -> Unit, modifier: Modifier) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val player = remember(url) {
        ExoPlayer.Builder(context).build().apply {
            val item = MediaItem.fromUri(url)
            val source = if (url.startsWith("rtsp", true)) {
                RtspMediaSource.Factory()
                    .setForceUseRtpTcp(true)
                    .setTimeoutMs(8_000)
                    .createMediaSource(item)
            } else {
                ProgressiveMediaSource.Factory(BoundDataSource.Factory(DashcamLink.current()))
                    .createMediaSource(item)
            }
            setMediaSource(source)
            playWhenReady = true
            prepare()
        }
    }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                onError(error.errorCodeName + " " + (error.message ?: "播放器错误") + " " + url)
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }
    AndroidView(
        factory = { ctx ->
            PlayerView(ctx).apply {
                useController = false
                this.player = player
            }
        },
        modifier = modifier,
    )
}

@Composable
private fun MjpegPicture(url: String, onError: (String) -> Unit, modifier: Modifier) {
    var frame by remember(url) { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
    LaunchedEffect(url) {
        val result = withContext(Dispatchers.IO) {
            runCatching {
                val call = DashcamLink.client().newCall(
                    okhttp3.Request.Builder().url(url).header("Connection", "close").build(),
                )
                call.execute().use { response ->
                    if (response.code !in 200..299) {
                        error("HTTP ${response.code} $url")
                    }
                    val input = response.body?.byteStream() ?: error("没有画面数据 $url")
                    while (isActive) {
                        val jpeg = JpegFrames.next(input) ?: break
                        val bitmap = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) ?: continue
                        frame = bitmap.asImageBitmap()
                    }
                }
            }
        }
        result.onFailure { onError(it.message ?: "MJPEG 中断 $url") }
    }
    val shown = frame
    if (shown != null) {
        Image(shown, contentDescription = "记录仪画面", modifier = modifier.fillMaxSize())
    }
}

private class BoundDataSource(private val network: Network?) : BaseDataSource(true) {
    private var connection: HttpURLConnection? = null
    private var input: InputStream? = null
    private var bytesRemaining = 0L

    override fun open(dataSpec: DataSpec): Long {
        val connection = openConnection(dataSpec)
        this.connection = connection
        connection.connectTimeout = 8_000
        connection.readTimeout = 20_000
        if (dataSpec.position > 0) {
            connection.setRequestProperty("Range", "bytes=${dataSpec.position}-")
        }
        connection.connect()
        val code = connection.responseCode
        if (code !in 200..299) error("HTTP $code ${dataSpec.uri.host}")
        input = connection.inputStream
        val length = connection.contentLengthLong
        bytesRemaining = if (length >= 0) length else Long.MAX_VALUE
        return if (length >= 0) length else androidx.media3.common.C.LENGTH_UNSET.toLong()
    }

    private fun openConnection(dataSpec: DataSpec): HttpURLConnection {
        val url = URL(dataSpec.uri.toString())
        val opened = network?.openConnection(url) ?: url.openConnection()
        return opened as HttpURLConnection
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (bytesRemaining == 0L) return androidx.media3.common.C.RESULT_END_OF_INPUT
        val stream = input ?: return androidx.media3.common.C.RESULT_END_OF_INPUT
        val read = stream.read(buffer, offset, length.coerceAtMost(if (bytesRemaining > Int.MAX_VALUE) length else bytesRemaining.toInt()))
        if (read == -1) return androidx.media3.common.C.RESULT_END_OF_INPUT
        if (bytesRemaining != Long.MAX_VALUE) bytesRemaining -= read
        return read
    }

    override fun getUri(): android.net.Uri? = connection?.url?.toString()?.let(android.net.Uri::parse)

    override fun close() {
        input?.close()
        connection?.disconnect()
        input = null
        connection = null
    }

    class Factory(private val network: Network?) : DataSource.Factory {
        override fun createDataSource(): DataSource = BoundDataSource(network)
    }
}
