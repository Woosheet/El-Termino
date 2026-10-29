package dev.eltermino.android

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.app.NotificationCompat
import dev.eltermino.app.PickedFile
import dev.eltermino.app.Platform
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AndroidPlatform(private val context: Context) : Platform {
    /** Set by the visible activity, which owns the document picker. */
    @Volatile
    var documentPicker: (suspend () -> Uri?)? = null

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

    private companion object {
        const val TRANSFERS = "transfers"
    }
}
