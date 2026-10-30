package dev.eltermino.android

import android.app.Activity
import android.app.Application
import android.os.Bundle
import dev.eltermino.app.AppState
import dev.eltermino.app.createAppServices
import dev.eltermino.core.ssh.mina.MinaPlatform
import dev.eltermino.core.vault.AndroidKeystoreMasterKeyProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
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
        lateinit var documents: DocumentFileSystem
        state = AppState(
            createAppServices(
                dataDir = dataDir,
                masterKey = AndroidKeystoreMasterKeyProvider(File(dataDir, "vault.key")),
                platform = platform,
                // App storage: other apps' files are reached through the system picker.
                localFilesRoot = getExternalFilesDir(null) ?: File(filesDir, "files").apply { mkdirs() },
                wrapLocalFiles = { documents = DocumentFileSystem(this, it); documents },
            ),
        )
        platform.documents = documents
        // The foreground service runs while there's a tab open or a transfer going.
        CoroutineScope(SupervisorJob() + Dispatchers.Main).launch {
            combine(state.sessions.tabs, state.transfers.summary) { tabs, transfers -> tabs.isNotEmpty() || transfers.active + transfers.waiting > 0 }
                .distinctUntilChanged()
                .drop(1)
                .collect { busy -> if (busy) SessionService.start(this@ElTerminoApp) else SessionService.stop(this@ElTerminoApp) }
        }
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                platform.startedActivities++
            }

            override fun onActivityStopped(activity: Activity) {
                platform.startedActivities--
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }
}
