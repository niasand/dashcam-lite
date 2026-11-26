package com.niasand.dashcamlite.ui

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

enum class FileViewMode { PREVIEW, LIST }

/** 纯函数，方便在不启动界面的情况下测记住的取值。 */
object FileViewModePref {
    const val KEY = "file_view_mode"

    fun encode(mode: FileViewMode): String = when (mode) {
        FileViewMode.PREVIEW -> "preview"
        FileViewMode.LIST -> "list"
    }

    fun decode(raw: String?): FileViewMode = when (raw) {
        "list" -> FileViewMode.LIST
        else -> FileViewMode.PREVIEW
    }
}

private val Context.fileViewDataStore by preferencesDataStore(name = "file_view")

class FileViewModeStore(private val context: Context) {
    private val key = stringPreferencesKey(FileViewModePref.KEY)

    val mode: Flow<FileViewMode> = context.fileViewDataStore.data.map { prefs ->
        FileViewModePref.decode(prefs[key])
    }

    suspend fun set(mode: FileViewMode) {
        context.fileViewDataStore.edit { prefs ->
            prefs[key] = FileViewModePref.encode(mode)
        }
    }
}
