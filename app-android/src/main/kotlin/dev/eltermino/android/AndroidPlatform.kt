package dev.eltermino.android

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import dev.eltermino.app.PickedFile
import dev.eltermino.app.Platform
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AndroidPlatform(private val context: Context) : Platform {
    /** Set by the visible activity, which owns the document picker. */
    @Volatile
    var documentPicker: (suspend () -> Uri?)? = null

    override val isDesktop = false

    override suspend fun pickFile(title: String): PickedFile? {
        val uri = documentPicker?.invoke() ?: return null
        return withContext(Dispatchers.IO) {
            val resolver = context.contentResolver
            val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            } ?: uri.lastPathSegment ?: "file"
            val bytes = resolver.openInputStream(uri)?.use { it.readBytes() } ?: return@withContext null
            PickedFile(name, bytes)
        }
    }

    override fun readSshConfig(): String? = null

    override fun resolveSshInclude(pattern: String): List<Pair<String, String>> = emptyList()

    override fun readLocalFile(path: String): ByteArray? = null
}
