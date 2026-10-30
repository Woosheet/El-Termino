package dev.eltermino.android

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.app.NotificationCompat
import dev.eltermino.app.AttachedFile
import dev.eltermino.app.PickedFile
import dev.eltermino.app.Platform
import dev.eltermino.core.ssh.RemoteFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AndroidPlatform(private val context: Context) : Platform {
    /** Set by the visible activity, which owns the document picker. */
    @Volatile
    var documentPicker: (suspend () -> Uri?)? = null

    /** Set by the visible activity too: the picker, for several documents at once. */
    @Volatile
    var documentsPicker: (suspend () -> List<Uri>)? = null

    /** Where picked documents are described. */
    lateinit var documents: DocumentFileSystem

    override val hasDocumentPicker = true

    /** The grant is kept so that a transfer outlives the activity that picked the file. */
    private fun keepAccess(uri: Uri, flags: Int) {
        runCatching { context.contentResolver.takePersistableUriPermission(uri, flags) }
    }

    override suspend fun pickDocuments(title: String): List<RemoteFile> {
        val uris = documentsPicker?.invoke().orEmpty()
        return uris.mapNotNull { uri ->
            withContext(Dispatchers.IO) { keepAccess(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            documents.stat(uri.toString())
        }
    }

    override suspend fun createDocument(name: String): String? {
        val uri = documentCreator?.invoke(name) ?: return null
        withContext(Dispatchers.IO) { keepAccess(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
        return uri.toString()
    }

    /** Set by the visible activity, which owns the system's prompt. */
    var biometricPrompt: (suspend (String) -> Boolean)? = null

    override val biometricAvailable: Boolean
        get() = BiometricManager.from(context).canAuthenticate(BIOMETRIC_WEAK or DEVICE_CREDENTIAL) == BiometricManager.BIOMETRIC_SUCCESS

    override suspend fun authenticate(reason: String): Boolean = biometricPrompt?.invoke(reason) == true

    /** Set by the visible activity too: where to create a document, named as suggested. */
    @Volatile
    var documentCreator: (suspend (String) -> Uri?)? = null

    /** Activities currently started, counted by [ElTerminoApp]. */
    @Volatile
    var startedActivities = 0

    override val isInForeground: Boolean get() = startedActivities > 0

    override fun notify(title: String, message: String) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(TRANSFERS, "Trasferimenti", NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(context, 2, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(context, TRANSFERS)
            .setSmallIcon(R.drawable.ic_stat_session)
            .setContentTitle(title)
            .setContentText(message)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        runCatching { manager.notify(TRANSFERS.hashCode(), notification) }
    }

    override val isDesktop = false

    override suspend fun pickFile(title: String): PickedFile? {
        val uri = documentPicker?.invoke() ?: return null
        return withContext(Dispatchers.IO) {
            val resolver = context.contentResolver
            val name = displayName(uri)
            val bytes = resolver.openInputStream(uri)?.use { it.readBytes() } ?: return@withContext null
            PickedFile(name, bytes)
        }
    }

    override suspend fun saveFile(suggestedName: String, bytes: ByteArray): Boolean {
        val uri = documentCreator?.invoke(suggestedName) ?: return false
        return withContext(Dispatchers.IO) {
            context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) } != null
        }
    }

    override fun readSshConfig(): String? = null

    override fun resolveSshInclude(pattern: String): List<Pair<String, String>> = emptyList()

    /** The document the user picks, kept readable across restarts for a form to send it again. */
    override suspend fun pickAttachment(title: String): AttachedFile? {
        val uri = documentPicker?.invoke() ?: return null
        return withContext(Dispatchers.IO) {
            runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            AttachedFile(uri.toString(), displayName(uri))
        }
    }

    private fun displayName(uri: Uri): String =
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        } ?: uri.lastPathSegment ?: "file"

    /** Only the documents picked with [pickAttachment]: Android gives apps no plain paths. */
    override fun readLocalFile(path: String): ByteArray? =
        if (!path.startsWith("content://")) null
        else runCatching { context.contentResolver.openInputStream(Uri.parse(path))?.use { it.readBytes() } }.getOrNull()

    private companion object {
        const val TRANSFERS = "transfers"
    }
}
