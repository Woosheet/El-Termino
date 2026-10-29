package dev.eltermino.android

import android.app.Application
import dev.eltermino.app.AppState
import dev.eltermino.app.createAppServices
import dev.eltermino.core.ssh.mina.MinaPlatform
import dev.eltermino.core.vault.AndroidKeystoreMasterKeyProvider
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
            ),
        )
    }
}
