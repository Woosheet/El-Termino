package dev.eltermino.android

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.graphics.Color
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.compose.runtime.LaunchedEffect
import dev.eltermino.ui.App
import dev.eltermino.ui.currentTheme
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private var pendingPick: CompletableDeferred<Uri?>? = null
    private val openDocument = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        pendingPick?.complete(uri)
        pendingPick = null
    }
    private val picker: suspend () -> Uri? = {
        withContext(Dispatchers.Main) {
            pendingPick?.complete(null)
            val result = CompletableDeferred<Uri?>().also { pendingPick = it }
            openDocument.launch(arrayOf("*/*"))
            result.await()
        }
    }

    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private val app get() = application as ElTerminoApp

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        app.platform.documentPicker = picker
        setContent {
            // Light or dark status bar icons, to match the theme rather than the system.
            val dark = currentTheme(app.state).dark
            LaunchedEffect(dark) {
                val style = if (dark) SystemBarStyle.dark(Color.TRANSPARENT) else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
            }
            App(app.state)
        }
        askForNotificationsWithFirstSession()
    }

    /**
     * Android 13+ hides the session notification without this permission. The service
     * runs either way; the notification is what lets you get back and close everything.
     */
    private fun askForNotificationsWithFirstSession() {
        if (Build.VERSION.SDK_INT < 33) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        lifecycleScope.launch {
            app.state.sessions.tabs.first { it.isNotEmpty() }
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    override fun onDestroy() {
        if (app.platform.documentPicker === picker) app.platform.documentPicker = null
        pendingPick?.complete(null)
        super.onDestroy()
    }
}
