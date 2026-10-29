package dev.eltermino.app

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class UpdateCheckerTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @AfterTest
    fun stop() = scope.cancel()

    private fun release(tag: String) = """
        {"tag_name":"$tag","html_url":"https://example.test/$tag","body":"note",
         "assets":[{"name":"El-Termino-${tag.removePrefix("v")}-macos-arm64.dmg","size":3,"browser_download_url":"https://example.test/$tag.dmg"}]}
    """.trimIndent()

    /** A packaged app on a Mac, with the network answering [answer]. */
    private class UpdatingPlatform(
        override val appVersion: String?,
        private val answer: () -> String,
    ) : Platform by TestPlatform() {
        override val updateAssetSuffix = "-macos-arm64.dmg"
        val installed = mutableListOf<String>()
        override suspend fun fetchText(url: String): String = answer()
        override suspend fun installUpdate(asset: ReleaseAsset, version: String, progress: (Float) -> Unit) {
            progress(0.5f)
            installed += "${asset.name} $version"
        }
    }

    private fun checker(platform: Platform, checkAtStartup: Boolean = false): UpdateChecker {
        val services = testServices(platform = TestPlatform())
        val withPlatform = AppServices(services.workspace, services.vault, services.connector, services.keyTool, platform, services.localFiles)
        runBlocking { withPlatform.workspace.update { it.copy(ui = it.ui.copy(checkUpdates = checkAtStartup)) } }
        return UpdateChecker(scope, withPlatform, startupDelayMillis = 0)
    }

    private fun UpdateChecker.settled(): UpdateState = runBlocking {
        withTimeout(5_000) { state.first { it !is UpdateState.Idle && it !is UpdateState.Checking && it !is UpdateState.Downloading } }
    }

    @Test
    fun aDevelopmentBuildDoesNotLook() {
        var asked = false
        val updates = checker(UpdatingPlatform(appVersion = null) { asked = true; release("v9.0.0") }, checkAtStartup = true)
        updates.check()
        assertEquals(UpdateState.Unsupported, updates.state.value)
        assertEquals(false, asked)
    }

    @Test
    fun findsANewerVersionAtStartup() {
        val updates = checker(UpdatingPlatform("1.0.0") { release("v1.0.1") }, checkAtStartup = true)
        val found = assertIs<UpdateState.Available>(updates.settled())
        assertEquals("1.0.1", found.release.version)
        assertEquals("El-Termino-1.0.1-macos-arm64.dmg", found.release.asset?.name)
    }

    @Test
    fun staysQuietWhenStartupChecksAreOff() {
        var asked = false
        val updates = checker(UpdatingPlatform("1.0.0") { asked = true; release("v1.0.1") }, checkAtStartup = false)
        Thread.sleep(200)
        assertEquals(UpdateState.Idle, updates.state.value)
        assertEquals(false, asked)
    }

    @Test
    fun saysWhenThisIsTheLatest() {
        val updates = checker(UpdatingPlatform("1.0.1") { release("v1.0.1") })
        updates.check()
        assertEquals(UpdateState.UpToDate, updates.settled())
    }

    @Test
    fun reportsANetworkFailure() {
        val updates = checker(UpdatingPlatform("1.0.0") { throw java.io.IOException("api.github.com non risponde") })
        updates.check()
        val failed = assertIs<UpdateState.Failed>(updates.settled())
        assertEquals("Non riesco a sapere se c'è una nuova versione: api.github.com non risponde", failed.message)
    }

    @Test
    fun installsTheReleaseFileAndWaitsForTheRestart() {
        val platform = UpdatingPlatform("1.0.0") { release("v1.0.1") }
        val updates = checker(platform)
        updates.check()
        val found = assertIs<UpdateState.Available>(updates.settled())
        updates.install(found.release)
        assertIs<UpdateState.Ready>(updates.settled())
        assertEquals(listOf("El-Termino-1.0.1-macos-arm64.dmg 1.0.1"), platform.installed)
        // Already in place: another check doesn't undo it.
        updates.check()
        assertIs<UpdateState.Ready>(updates.state.value)
    }
}
