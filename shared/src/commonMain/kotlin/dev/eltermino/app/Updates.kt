package dev.eltermino.app

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/** Where tagged versions are published, by .github/workflows/release.yml. */
const val LATEST_RELEASE_URL = "https://api.github.com/repos/Woosheet/El-Termino/releases/latest"

/** A published version: its notes, its page, and the file that installs it on this device, if any. */
data class Release(val version: String, val notes: String, val pageUrl: String, val asset: ReleaseAsset?)

data class ReleaseAsset(val name: String, val url: String, val sizeBytes: Long)

object Releases {
    /** GitHub's answer for the latest release, keeping the file whose name ends in [assetSuffix]. */
    fun parse(json: String, assetSuffix: String?): Release {
        val root = Json.parseToJsonElement(json).jsonObject
        val tag = root.string("tag_name") ?: throw IllegalArgumentException("The release has no tag")
        val asset = assetSuffix?.let { suffix ->
            root["assets"]?.jsonArray.orEmpty().map { it.jsonObject }
                .firstOrNull { it.string("name")?.endsWith(suffix) == true }
                ?.let { file ->
                    val url = file.string("browser_download_url") ?: return@let null
                    ReleaseAsset(file.string("name")!!, url, file["size"]?.jsonPrimitive?.longOrNull ?: 0L)
                }
        }
        return Release(tag.removePrefix("v"), root.string("body").orEmpty(), root.string("html_url").orEmpty(), asset)
    }

    /** Whether [candidate] comes after [current], comparing 1.2.3 number by number. */
    fun isNewer(candidate: String, current: String): Boolean {
        val a = numbers(candidate)
        val b = numbers(current)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    private fun numbers(version: String) = version.removePrefix("v").substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }

    private fun JsonObject.string(key: String) = this[key]?.jsonPrimitive?.contentOrNull
}

/** Where the app stands with updates, for the status bar, the dialog and the settings. */
sealed interface UpdateState {
    /** A development build, or a device where the app doesn't look for versions. */
    data object Unsupported : UpdateState

    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val release: Release) : UpdateState
    data class Downloading(val release: Release, val progress: Float) : UpdateState

    /** In place next to this version: it takes over when the app closes. */
    data class Ready(val release: Release) : UpdateState

    data class Failed(val message: String, val release: Release?) : UpdateState
}

/**
 * Looks for a newer release at startup, when the settings allow it, and installs it on
 * request. Checks and installs run one at a time.
 */
class UpdateChecker(
    private val scope: CoroutineScope,
    private val services: AppServices,
    startupDelayMillis: Long = 3_000,
) {
    private val platform get() = services.platform

    /** This build's version; null for a development build. */
    val current: String? = services.platform.appVersion

    private val mutableState = MutableStateFlow<UpdateState>(if (current == null) UpdateState.Unsupported else UpdateState.Idle)
    val state: StateFlow<UpdateState> = mutableState.asStateFlow()

    private val mutableShowing = MutableStateFlow(false)

    /** Whether the update dialog is open. */
    val showing: StateFlow<Boolean> = mutableShowing.asStateFlow()

    init {
        if (current != null && services.workspace.workspace.value.ui.checkUpdates) {
            scope.launch {
                // After the window is up, so the check doesn't slow the start.
                delay(startupDelayMillis)
                check()
            }
        }
    }

    fun show() {
        mutableShowing.value = true
    }

    fun hide() {
        mutableShowing.value = false
    }

    /** Asks for the latest release; the answer lands in [state]. */
    fun check() {
        val version = current ?: return
        if (busy()) return
        mutableState.value = UpdateState.Checking
        scope.launch {
            mutableState.value = try {
                val release = Releases.parse(platform.fetchText(platform.releasesUrl), platform.updateAssetSuffix)
                if (Releases.isNewer(release.version, version)) UpdateState.Available(release) else UpdateState.UpToDate
            } catch (e: Exception) {
                UpdateState.Failed("Non riesco a sapere se c'è una nuova versione: ${e.message ?: e::class.simpleName}", null)
            }
        }
    }

    /** Downloads [release] and puts it in place; it takes over when the app closes. */
    fun install(release: Release) {
        val asset = release.asset ?: return
        if (busy()) return
        mutableState.value = UpdateState.Downloading(release, 0f)
        scope.launch {
            mutableState.value = try {
                platform.installUpdate(asset, release.version) { mutableState.value = UpdateState.Downloading(release, it.coerceIn(0f, 1f)) }
                UpdateState.Ready(release)
            } catch (e: Exception) {
                UpdateState.Failed(e.message ?: "L'aggiornamento non è riuscito", release)
            }
        }
    }

    private fun busy() = mutableState.value.let { it is UpdateState.Checking || it is UpdateState.Downloading || it is UpdateState.Ready }
}
