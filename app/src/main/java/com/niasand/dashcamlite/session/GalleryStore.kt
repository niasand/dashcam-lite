package com.niasand.dashcamlite.session

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.niasand.dashcamlite.protocol.FileDownloader
import com.niasand.dashcamlite.protocol.HttpTransport
import com.niasand.dashcamlite.protocol.MediaFile
import com.niasand.dashcamlite.protocol.MediaKind
import com.niasand.dashcamlite.protocol.OkHttpTransport
import java.io.File

class GalleryStore(private val context: Context) {
    fun downloadToGallery(
        file: MediaFile,
        transport: HttpTransport = OkHttpTransport(),
        onProgress: (Long, Long) -> Unit = { _, _ -> },
    ): Uri {
        val cache = File(context.cacheDir, "downloads")
        val partial = File(cache, file.name + ".part")
        FileDownloader(transport).download(file.url, partial, onProgress)
        val collection = if (file.kind == MediaKind.PHOTO) {
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        } else {
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        }
        val relative = if (file.kind == MediaKind.PHOTO) {
            Environment.DIRECTORY_PICTURES + "/DashcamLite"
        } else {
            Environment.DIRECTORY_MOVIES + "/DashcamLite"
        }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime(file.name))
            if (Build.VERSION.SDK_INT >= 29) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, relative)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(collection, values) ?: error("无法写入相册")
        resolver.openOutputStream(uri)?.use { output ->
            partial.inputStream().use { it.copyTo(output) }
        } ?: error("无法打开相册文件")
        if (Build.VERSION.SDK_INT >= 29) {
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        }
        partial.delete()
        return uri
    }

    private fun mime(name: String): String = when {
        name.endsWith(".jpg", true) || name.endsWith(".jpeg", true) -> "image/jpeg"
        name.endsWith(".png", true) -> "image/png"
        name.endsWith(".ts", true) -> "video/mp2t"
        else -> "video/mp4"
    }
}
