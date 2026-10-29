package dev.eltermino.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import dev.eltermino.app.TabStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Keeps the process in the foreground while sessions are open, so Android doesn't kill
 * the SSH connections once the app goes to the background. The notification counts the
 * sessions and can close them all.
 */
class SessionService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val app get() = application as ElTerminoApp

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Sessioni aperte", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Resta visibile finché ci sono sessioni SSH aperte"
                setShowBadge(false)
            },
        )
        val first = notification(0, 0)
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION, first, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION, first)
        }
        scope.launch { counts().collect { (open, total) -> manager.notify(NOTIFICATION, notification(open, total)) } }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CLOSE_ALL) app.state.sessions.closeAll()
        // Sessions don't survive the process, so there's nothing to restart into.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    /** (connected, total) tab counts. */
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun counts() = app.state.sessions.tabs.flatMapLatest { tabs ->
        if (tabs.isEmpty()) flowOf(0 to 0)
        else combine(tabs.map { it.status }) { statuses -> statuses.count { it is TabStatus.Open } to statuses.size }
    }

    private fun notification(open: Int, total: Int): Notification {
        val show = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val closeAll = PendingIntent.getService(
            this, 1,
            Intent(this, SessionService::class.java).setAction(ACTION_CLOSE_ALL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val text = when {
            total == 0 -> "Nessuna sessione"
            open == total -> if (total == 1) "1 sessione connessa" else "$total sessioni connesse"
            else -> "$open di $total sessioni connesse"
        }
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_session)
            .setContentTitle("El Termino")
            .setContentText(text)
            .setContentIntent(show)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(0, "Chiudi tutte", closeAll)
            .build()
    }

    companion object {
        private const val CHANNEL = "sessions"
        private const val NOTIFICATION = 1
        private const val ACTION_CLOSE_ALL = "dev.eltermino.CLOSE_ALL"

        fun start(context: Context) = ContextCompat.startForegroundService(context, Intent(context, SessionService::class.java))

        fun stop(context: Context) = context.stopService(Intent(context, SessionService::class.java))
    }
}
