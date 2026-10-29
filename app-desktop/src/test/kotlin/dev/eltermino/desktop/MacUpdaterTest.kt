package dev.eltermino.desktop

import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The real steps of an update, on small signed stand-ins for the app, away from /Applications. */
class MacUpdaterTest {
    private val root = Files.createTempDirectory("eltermino-updater-test").toFile()

    @BeforeTest
    fun onlyOnMac() = assumeTrue("mac" in System.getProperty("os.name").lowercase())

    @AfterTest
    fun clean() {
        root.walkTopDown().forEach { it.setWritable(true) }
        root.deleteRecursively()
    }

    private fun sh(vararg command: String): String {
        val process = ProcessBuilder(*command).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        check(process.waitFor() == 0) { "${command.joinToString(" ")}: $output" }
        return output
    }

    /** A tiny signed bundle that says it is El Termino [version]. */
    private fun app(dir: String, version: String, id: String = "dev.eltermino.desktop"): File {
        val app = File(File(root, dir), "El Termino.app")
        File(app, "Contents/MacOS").mkdirs()
        File(app, "Contents/Info.plist").writeText(
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
            <plist version="1.0"><dict>
              <key>CFBundleIdentifier</key><string>$id</string>
              <key>CFBundleShortVersionString</key><string>$version</string>
              <key>CFBundleExecutable</key><string>El Termino</string>
              <key>CFBundlePackageType</key><string>APPL</string>
            </dict></plist>
            """.trimIndent(),
        )
        // Any real executable will do, signed again as this bundle's own.
        File("/usr/bin/true").copyTo(File(app, "Contents/MacOS/El Termino")).setExecutable(true)
        sh("codesign", "--force", "--sign", "-", app.path)
        return app
    }

    /** The DMG the release workflow would publish, made by the same script. */
    private fun dmg(app: File, name: String): File {
        val dmg = File(root, name)
        sh("sh", "packaging/make-dmg.sh", app.path, "packaging/icon.icns", dmg.path)
        return dmg
    }

    private fun versionOf(app: File) =
        sh("/usr/libexec/PlistBuddy", "-c", "Print :CFBundleShortVersionString", File(app, "Contents/Info.plist").path).trim()

    private fun eventually(timeoutMs: Long = 20_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "timed out" }
            Thread.sleep(100)
        }
    }

    @Test
    fun replacesTheAppOnceItHasClosed() {
        val installed = app("Applications", "1.0.0")
        val release = dmg(app("build", "1.0.1"), "El-Termino-1.0.1-macos-arm64.dmg")
        val running = ProcessBuilder("sleep", "60").start()
        val progress = mutableListOf<Float>()

        MacUpdater(installed, pid = running.pid()).install(release.toURI().toString(), "1.0.1", release.length()) { progress += it }

        assertEquals(1f, progress.last())
        val staged = File(installed.parentFile, ".El Termino.app.update")
        assertTrue(staged.isDirectory, "the new version waits next to the old one")
        assertEquals("1.0.0", versionOf(installed), "nothing changes while the app runs")

        running.destroy()
        eventually { !staged.exists() && versionOf(installed) == "1.0.1" }
        sh("codesign", "--verify", "--deep", "--strict", installed.path)
        assertFalse(File(installed.parentFile, "El Termino.app.old").exists())
    }

    @Test
    fun refusesAnotherApp() {
        val installed = app("Applications", "1.0.0")
        val release = dmg(app("build", "1.0.1", id = "com.example.other"), "other.dmg")
        val error = assertFailsWith<UpdateException> {
            MacUpdater(installed, pid = ProcessHandle.current().pid()).install(release.toURI().toString(), "1.0.1", release.length()) {}
        }
        assertEquals("Il file scaricato non è El Termino (com.example.other)", error.message)
        assertFalse(File(installed.parentFile, ".El Termino.app.update").exists())
        assertEquals(before, workFolders(), "a refused update leaves no work folder behind")
    }

    private val before = workFolders()

    private fun workFolders() =
        File(System.getProperty("java.io.tmpdir")).listFiles { f -> f.name.startsWith("eltermino-update") }.orEmpty().map { it.name }.toSet()

    @Test
    fun refusesAVersionOtherThanTheAnnouncedOne() {
        val installed = app("Applications", "1.0.0")
        val release = dmg(app("build", "1.0.2"), "wrong.dmg")
        val error = assertFailsWith<UpdateException> {
            MacUpdater(installed, pid = ProcessHandle.current().pid()).install(release.toURI().toString(), "1.0.1", release.length()) {}
        }
        assertEquals("Il file scaricato è la versione 1.0.2, non la 1.0.1", error.message)
    }

    @Test
    fun refusesAFolderItCannotWriteTo() {
        val installed = app("Applications", "1.0.0")
        installed.parentFile.setWritable(false)
        val error = assertFailsWith<UpdateException> {
            MacUpdater(installed, pid = ProcessHandle.current().pid()).install("file:///nowhere.dmg", "1.0.1", 0) {}
        }
        assertTrue(error.message!!.startsWith("Non posso scrivere in "))
    }
}
