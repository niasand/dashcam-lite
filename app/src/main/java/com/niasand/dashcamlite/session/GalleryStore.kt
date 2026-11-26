package com.niasand.dashcamlite.session

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.niasand.dashcamlite.protocol.ChunkCopy
import com.niasand.dashcamlite.protocol.MediaFile
import com.niasand.dashcamlite.protocol.MediaKind
import java.io.File

class GalleryStore(private val context: Context) {
    fun publish(file: MediaFile, localFile: File): Uri {
        if (!localFile.exists() || localFile.length() <= 0L) {
            error("保存到相册失败：下载文件是空的")
        }
        val photo = file.kind == MediaKind.PHOTO
        val collection = if (Build.VERSION.SDK_INT >= 29) {
            val volume = MediaStore.VOLUME_EXTERNAL_PRIMARY
            if (photo) MediaStore.Images.Media.getContentUri(volume) else MediaStore.Video.Media.getContentUri(volume)
        } else if (photo) {
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        } else {
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        }
        val relative = if (photo) {
            Environment.DIRECTORY_PICTURES + "/DashcamLite"
        } else {
            Environment.DIRECTORY_MOVIES + "/DashcamLite"
        }
        val display = safeDisplayName(file.name)
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, display)
            put(MediaStore.MediaColumns.MIME_TYPE, mime(display))
            if (Build.VERSION.SDK_INT >= 29) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, relative)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        }
        val resolver = context.contentResolver
        val uri = try {
            resolver.insert(collection, values)
        } catch (error: Exception) {
            throw IllegalStateException("保存到相册失败：${error.message}", error)
        } ?: error("保存到相册失败：系统没有返回文件地址（$relative）")
        try {
            resolver.openOutputStream(uri)?.use { output ->
                localFile.inputStream().use { input -> ChunkCopy.copy(input, output) }
            } ?: error("保存到相册失败：打不开 $relative/$display")
            if (Build.VERSION.SDK_INT >= 29) {
                val pending = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
                val updated = resolver.update(uri, pending, null, null)
                if (updated == 0) error("保存到相册失败：文件仍处于未完成状态")
            }
        } catch (error: Exception) {
            resolver.delete(uri, null, null)
            if (error is IllegalStateException) throw error
            throw IllegalStateException("保存到相册失败：${error.message}", error)
        }
        localFile.delete()
        return uri
    }

    private fun safeDisplayName(name: String): String {
        val base = name.substringAfterLast('/').substringAfterLast('\\').ifBlank { "dashcam.bin" }
        return base.replace(Regex("""[\\/:*?"<>|]"""), "_")
    }

    private fun mime(name: String): String = when {
        name.endsWith(".jpg", true) || name.endsWith(".jpeg", true) -> "image/jpeg"
        name.endsWith(".png", true) -> "image/png"
        name.endsWith(".ts", true) -> "video/mp2t"
        else -> "video/mp4"
    }
}
