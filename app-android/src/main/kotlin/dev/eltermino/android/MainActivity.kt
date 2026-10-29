package dev.eltermino.android

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import dev.eltermino.ui.App
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
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

    private val app get() = application as ElTerminoApp

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        app.platform.documentPicker = picker
        setContent { App(app.state) }
    }

    override fun onDestroy() {
        if (app.platform.documentPicker === picker) app.platform.documentPicker = null
        pendingPick?.complete(null)
        super.onDestroy()
    }
}
