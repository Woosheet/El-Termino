package dev.eltermino.android

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.graphics.Color
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import androidx.compose.runtime.LaunchedEffect
import dev.eltermino.ui.App
import dev.eltermino.ui.currentTheme
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : FragmentActivity() {
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

    private var pendingPicks: CompletableDeferred<List<Uri>>? = null
    private val openDocuments = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        pendingPicks?.complete(uris)
        pendingPicks = null
    }
    private val documentsPicker: suspend () -> List<Uri> = {
        withContext(Dispatchers.Main) {
            pendingPicks?.complete(emptyList())
            val result = CompletableDeferred<List<Uri>>().also { pendingPicks = it }
            openDocuments.launch(arrayOf("*/*"))
            result.await()
        }
    }

    private val biometricPrompt: suspend (String) -> Boolean = { reason ->
        withContext(Dispatchers.Main) {
            val result = CompletableDeferred<Boolean>()
            val callback = object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(authentication: BiometricPrompt.AuthenticationResult) {
                    result.complete(true)
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    result.complete(false)
                }
            }
            val info = BiometricPrompt.PromptInfo.Builder()
                .setTitle(reason)
                .setAllowedAuthenticators(BIOMETRIC_WEAK or DEVICE_CREDENTIAL)
                .build()
            BiometricPrompt(this@MainActivity, ContextCompat.getMainExecutor(this@MainActivity), callback).authenticate(info)
            result.await()
        }
    }

    private var pendingCreate: CompletableDeferred<Uri?>? = null
    private val createDocument = registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        pendingCreate?.complete(uri)
        pendingCreate = null
    }
    private val creator: suspend (String) -> Uri? = { name ->
        withContext(Dispatchers.Main) {
            pendingCreate?.complete(null)
            val result = CompletableDeferred<Uri?>().also { pendingCreate = it }
            createDocument.launch(name)
            result.await()
        }
    }

    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private val app get() = application as ElTerminoApp

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        app.platform.documentPicker = picker
        app.platform.documentCreator = creator
        app.platform.documentsPicker = documentsPicker
        app.platform.biometricPrompt = biometricPrompt
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
        if (app.platform.documentCreator === creator) app.platform.documentCreator = null
        if (app.platform.documentsPicker === documentsPicker) app.platform.documentsPicker = null
        if (app.platform.biometricPrompt === biometricPrompt) app.platform.biometricPrompt = null
        pendingPick?.complete(null)
        pendingCreate?.complete(null)
        pendingPicks?.complete(emptyList())
        super.onDestroy()
    }
}
