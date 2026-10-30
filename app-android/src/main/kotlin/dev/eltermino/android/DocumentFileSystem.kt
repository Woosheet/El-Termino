package dev.eltermino.android

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import dev.eltermino.app.FileSystem
import dev.eltermino.core.ssh.ByteSink
import dev.eltermino.core.ssh.ByteSource
import dev.eltermino.core.ssh.RemoteFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * This device's files: [local] for the app's folder, the content resolver for documents
 * from the system picker, whose content:// address stands in for a path. A document whose
 * provider doesn't give its size reports 0, which transfers show without a percentage.
 */
class DocumentFileSystem(private val context: Context, private val local: FileSystem) : FileSystem by local {
    private val resolver get() = context.contentResolver

    private fun String.isDocument() = startsWith("content://")

    override suspend fun stat(path: String): RemoteFile? {
        if (!path.isDocument()) return local.stat(path)
        return withContext(Dispatchers.IO) {
            val projection = arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
            resolver.query(Uri.parse(path), projection, null, null, null)?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                val name = if (cursor.isNull(0)) null else cursor.getString(0)
                val size = if (cursor.isNull(1)) 0L else cursor.getLong(1)
                RemoteFile(path, safeName(name), RemoteFile.Type.File, size, permissions = 0, modifiedEpochSeconds = 0)
            }
        }
    }

    /** It names the file on the other side, so it can't be allowed to climb out of the folder. */
    private fun safeName(name: String?) = name?.substringAfterLast('/')?.takeUnless { it.isBlank() || it == "." || it == ".." } ?: "file"

    override suspend fun openRead(path: String, offset: Long): ByteSource {
        if (!path.isDocument()) return local.openRead(path, offset)
        return withContext(Dispatchers.IO) {
            val stream = resolver.openInputStream(Uri.parse(path)) ?: throw IOException("Impossibile leggere il documento")
            var left = offset
            while (left > 0) left -= stream.skip(left).also { if (it <= 0) throw IOException("Il documento è più corto del previsto") }
            StreamSource(stream)
        }
    }

    override suspend fun openWrite(path: String, offset: Long): ByteSink {
        if (!path.isDocument()) return local.openWrite(path, offset)
        return withContext(Dispatchers.IO) {
            StreamSink(resolver.openOutputStream(Uri.parse(path), if (offset > 0) "wa" else "wt") ?: throw IOException("Impossibile scrivere il documento"))
        }
    }

    override suspend fun delete(path: String, recursive: Boolean) {
        if (!path.isDocument()) return local.delete(path, recursive)
        withContext(Dispatchers.IO) { DocumentsContract.deleteDocument(resolver, Uri.parse(path)) }
    }

    // Documents have no dates or permissions to keep.
    override suspend fun chmod(path: String, mode: Int) {
        if (!path.isDocument()) local.chmod(path, mode)
    }

    override suspend fun setModified(path: String, epochSeconds: Long) {
        if (!path.isDocument()) local.setModified(path, epochSeconds)
    }

    private class StreamSource(private val stream: InputStream) : ByteSource {
        override suspend fun read(buffer: ByteArray): Int = withContext(Dispatchers.IO) { stream.read(buffer) }
        override suspend fun close() = withContext(Dispatchers.IO) { stream.close() }
    }

    private class StreamSink(private val stream: OutputStream) : ByteSink {
        override suspend fun write(buffer: ByteArray, length: Int) = withContext(Dispatchers.IO) { stream.write(buffer, 0, length) }
        override suspend fun close() = withContext(Dispatchers.IO) { stream.close() }
    }
}
