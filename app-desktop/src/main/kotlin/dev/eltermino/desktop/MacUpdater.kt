package dev.eltermino.desktop

import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLConnection
import java.nio.file.Files
import java.util.concurrent.TimeUnit

/** Something went wrong with an update, said so the user can act on it. */
internal class UpdateException(message: String) : Exception(message)

/**
 * Replaces this app's bundle with the one in a release DMG. The app in the DMG must have this
 * bundle's id, the version the release announces and a valid signature; it's copied next to
 * this one, and a small script waits for [pid] to end, swaps the two and, when asked, opens
 * the new one.
 */
internal class MacUpdater(private val bundle: File, private val pid: Long = ProcessHandle.current().pid()) {
    private val work: File by lazy { Files.createTempDirectory("eltermino-update").toFile() }
    private val staged = File(bundle.parentFile, ".${bundle.name}.update")
    private var swapping = false

    fun install(url: String, version: String, expectedBytes: Long, progress: (Float) -> Unit) {
        try {
            prepare(url, version, expectedBytes, progress)
        } catch (e: Exception) {
            // Nothing is waiting on the work folder until the swap starts.
            if (!swapping) work.deleteRecursively()
            throw e
        }
        if (!swapping) {
            startSwap()
            swapping = true
        }
    }

    private fun prepare(url: String, version: String, expectedBytes: Long, progress: (Float) -> Unit) {
        if (!bundle.parentFile.canWrite()) {
            throw UpdateException("Non posso scrivere in ${bundle.parent}. Sposta El Termino in Applicazioni, oppure scarica la nuova versione dalla pagina della release.")
        }
        val id = plist(bundle, "CFBundleIdentifier")
        val dmg = File(work, "update.dmg")
        download(url, dmg, expectedBytes, progress)
        val mount = File(work, "mount").apply { mkdirs() }
        run("hdiutil", "attach", "-nobrowse", "-readonly", "-noautoopen", "-mountpoint", mount.path, dmg.path, failure = "Il file scaricato non si apre")
        try {
            val app = mount.listFiles { f -> f.name.endsWith(".app") }?.singleOrNull()
                ?: throw UpdateException("Nel file scaricato non c'è l'app")
            val foundId = plist(app, "CFBundleIdentifier")
            if (foundId != id) throw UpdateException("Il file scaricato non è El Termino ($foundId)")
            val foundVersion = plist(app, "CFBundleShortVersionString")
            if (foundVersion != version) throw UpdateException("Il file scaricato è la versione $foundVersion, non la $version")
            run("codesign", "--verify", "--deep", "--strict", app.path, failure = "La firma dell'app scaricata non è valida")
            staged.deleteRecursively()
            run("ditto", app.path, staged.path, failure = "Non riesco a copiare la nuova versione in ${bundle.parent}")
        } finally {
            runCatching { run("hdiutil", "detach", "-quiet", "-force", mount.path, failure = "") }
            dmg.delete()
        }
    }

    /** Has the new version open once this one has closed. */
    fun relaunchAfterExit() {
        if (swapping) File(work, "relaunch").createNewFile()
    }

    private fun startSwap() {
        val script = File(work, "swap.sh").apply { writeText(SWAP) }
        ProcessBuilder("/bin/sh", script.path, pid.toString(), bundle.path, staged.path, work.path)
            .redirectInput(ProcessBuilder.Redirect.from(File("/dev/null")))
            .redirectOutput(File(work, "swap.log"))
            .redirectErrorStream(true)
            .start()
    }

    private fun download(url: String, to: File, expectedBytes: Long, progress: (Float) -> Unit) {
        val connection = connect(url)
        val total = connection.contentLengthLong.takeIf { it > 0 } ?: expectedBytes
        var done = 0L
        connection.getInputStream().use { input ->
            to.outputStream().use { output ->
                val buffer = ByteArray(256 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    output.write(buffer, 0, read)
                    done += read
                    if (total > 0) progress(done.toFloat() / total)
                }
            }
        }
        if (expectedBytes > 0 && done != expectedBytes) throw UpdateException("Il download si è interrotto: ${done / 1_000_000} MB su ${expectedBytes / 1_000_000}")
    }

    private fun plist(app: File, key: String) =
        run("/usr/libexec/PlistBuddy", "-c", "Print :$key", File(app, "Contents/Info.plist").path, failure = "Non riesco a leggere ${app.name}").trim()

    private fun run(vararg command: String, failure: String): String {
        val process = ProcessBuilder(*command).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        if (!process.waitFor(2, TimeUnit.MINUTES)) {
            process.destroyForcibly()
            throw UpdateException("$failure: ${command.first()} non risponde")
        }
        if (process.exitValue() != 0) throw UpdateException("$failure: ${output.trim().lines().lastOrNull().orEmpty()}")
        return output
    }

    private companion object {
        /** Waits for the app to close, puts the new version in place of the old one and maybe opens it. */
        const val SWAP = """
pid="${'$'}1"; app="${'$'}2"; staged="${'$'}3"; work="${'$'}4"
while kill -0 "${'$'}pid" 2>/dev/null; do sleep 1; done
if [ -d "${'$'}staged" ]; then
  rm -rf "${'$'}app.old"
  if mv "${'$'}app" "${'$'}app.old"; then
    if mv "${'$'}staged" "${'$'}app"; then rm -rf "${'$'}app.old"; else mv "${'$'}app.old" "${'$'}app"; fi
  fi
fi
if [ -f "${'$'}work/relaunch" ]; then open "${'$'}app"; fi
rm -rf "${'$'}work"
"""
    }
}

/** A GET to [url], http(s) or file; fails with the server's status when it isn't 200. */
internal fun connect(url: String, accept: String? = null): URLConnection {
    val connection = URI(url).toURL().openConnection().apply {
        connectTimeout = 15_000
        readTimeout = 30_000
        setRequestProperty("User-Agent", "El Termino")
        accept?.let { setRequestProperty("Accept", it) }
    }
    if (connection is HttpURLConnection && connection.responseCode != 200) {
        throw UpdateException("$url risponde ${connection.responseCode}")
    }
    return connection
}
