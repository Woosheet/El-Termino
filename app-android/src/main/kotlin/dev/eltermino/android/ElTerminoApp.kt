package dev.eltermino.android

import android.app.Application
import dev.eltermino.app.AppState
import dev.eltermino.app.createAppServices
import dev.eltermino.core.ssh.mina.MinaPlatform
import dev.eltermino.core.vault.AndroidKeystoreMasterKeyProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.io.File

/** Holds the app state for the whole process, so sessions outlive the activity. */
class ElTerminoApp : Application() {
    lateinit var platform: AndroidPlatform
        private set
    lateinit var state: AppState
        private set

    override fun onCreate() {
        super.onCreate()
        MinaPlatform.setUpAndroid(filesDir)
        val dataDir = File(filesDir, "data")
        platform = AndroidPlatform(this)
        state = AppState(
            createAppServices(
                dataDir = dataDir,
                masterKey = AndroidKeystoreMasterKeyProvider(File(dataDir, "vault.key")),
                platform = platform,
                // App storage: other apps' files are reached through the system picker.
                localFilesRoot = getExternalFilesDir(null) ?: File(filesDir, "files").apply { mkdirs() },
            ),
        )
        // The foreground service runs exactly while there's at least one tab.
        CoroutineScope(SupervisorJob() + Dispatchers.Main).launch {
            state.sessions.tabs.map { it.isNotEmpty() }.distinctUntilChanged().drop(1).collect { any ->
                if (any) SessionService.start(this@ElTerminoApp) else SessionService.stop(this@ElTerminoApp)
            }
        }
    }
}
