package dev.eltermino.app

import dev.eltermino.core.data.Host
import dev.eltermino.core.data.newId
import dev.eltermino.core.ssh.ByteSink
import dev.eltermino.core.ssh.ByteSource
import dev.eltermino.core.ssh.RemoteFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import java.io.File
import java.io.IOException
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/** Phase 3 against the testenv servers: one queue across hosts, pause, conflicts, drops, editor. */
class TransferFlowTest {
    private val services = testServices()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val actions = WorkspaceActions(services)
    private val sessions = SessionsController(services, actions, scope, ReconnectPolicy(List(40) { 150.milliseconds }))
    private val connections = sessions.connections
    private val transfers = TransferManager(scope, services, connections)
    private val local = runBlocking { services.localFiles!!.home() }
    private val dir = "/tmp/tr-${newId().take(8)}"

    init {
        scope.launch {
            connections.prompter.prompt.filterNotNull().collect { if (it is PendingPrompt.HostKey) it.answer(true) else it.cancel() }
        }
    }

    @AfterEach
    fun tearDown() {
        sessions.closeAll()
        Thread.sleep(300)
        scope.cancel()
    }

    private fun hosts(): Triple<Host, Host, Host> = runBlocking {
        val identity = actions.keyIdentity()
        val basic = actions.testHost("basic", TestEnv.BASIC_PORT, identity)
        val bastion = actions.testHost("bastion", TestEnv.BASTION_PORT, identity)
        val internal = actions.testHost("internal", 22, identity, address = "internal", jumpHostId = bastion.id)
        Triple(basic, bastion, internal)
    }

    private fun Host.sh(command: String) = runBlocking { connections.run(this@sh, command) }

    private fun entry(fs: Endpoint, path: String): RemoteFile = runBlocking {
        val system = if (fs is Endpoint.Remote) connections.files(services.workspace.workspace.value.host(fs.hostId)!!) else services.localFiles!!
        try {
            system.stat(path)!!
        } finally {
            (system as? RemoteFileSystem)?.release()
        }
    }

    private fun awaitDone(vararg list: Transfer, timeoutMs: Long = 120_000) = runBlocking {
        withTimeout(timeoutMs) {
            list.forEach { t -> t.state.first { it.isFinished } }
        }
        list.forEach { assertIs<TransferState.Done>(it.state.value, "${it.name}: ${it.state.value}") }
    }

    /** A queue whose local files, like a document from a provider that won't tell, report size 0 when they're the source. */
    private fun unknownSizes(): TransferManager {
        val real = services.localFiles!!
        val files = object : FileSystem by real {
            override suspend fun stat(path: String) = real.stat(path)?.let { if ("/dest-" in path) it else it.copy(size = 0, modifiedEpochSeconds = 0) }
        }
        val blind = AppServices(services.workspace, services.vault, services.connector, services.keyTool, services.platform, localFiles = files)
        return TransferManager(scope, blind, connections)
    }

    @Test
    fun saveAsWritesExactlyWhereTheSystemSaid() {
        val bytes = Random.nextBytes(4_000)
        val source = localFile("from-${newId().take(8)}.bin", bytes)
        val chosen = File(local, "scelto-${newId().take(8)}.bin").apply { writeBytes(ByteArray(0)) }
        val transfer = transfers.saveAs(Endpoint.Local, entry(Endpoint.Local, source.path), chosen.path)
        awaitDone(transfer)
        assertTrue(bytes.contentEquals(chosen.readBytes()))
    }

    @Test
    fun aSourceOfUnknownSizeIsCopiedWhole() {
        val bytes = Random.nextBytes(4_000)
        val source = localFile("nosize-${newId().take(8)}.bin", bytes)
        val folder = File(local, "dest-${newId().take(8)}").apply { mkdirs() }
        val transfer = unknownSizes().saveAs(Endpoint.Local, entry(Endpoint.Local, source.path), File(folder, "x.bin").path)
        awaitDone(transfer)
        assertTrue(bytes.contentEquals(File(folder, "x.bin").readBytes()))
    }

    /** A queue over local files changed by [wrap], as a provider of documents would behave. */
    private fun queueOver(wrap: (FileSystem) -> FileSystem): TransferManager {
        val files = wrap(services.localFiles!!)
        val blind = AppServices(services.workspace, services.vault, services.connector, services.keyTool, services.platform, localFiles = files)
        return TransferManager(scope, blind, connections)
    }

    @Test
    fun aChosenDocumentStartsOverAfterAFailure() {
        val bytes = Random.nextBytes(4_000)
        val source = localFile("doc-${newId().take(8)}.bin", bytes)
        val chosen = File(local, "scelto-${newId().take(8)}.bin").apply { writeBytes(ByteArray(0)) }
        val offsets = mutableListOf<Long>()
        var broken = true
        val queue = queueOver { real ->
            object : FileSystem by real {
                override suspend fun openWrite(path: String, offset: Long): ByteSink {
                    offsets += offset
                    return real.openWrite(path, offset)
                }

                override suspend fun openRead(path: String, offset: Long): ByteSource {
                    val inner = real.openRead(path, offset)
                    return object : ByteSource by inner {
                        var calls = 0
                        override suspend fun read(buffer: ByteArray): Int {
                            if (broken && calls++ == 1) { broken = false; throw IOException("letto a metà") }
                            return inner.read(buffer)
                        }
                    }
                }
            }
        }
        val transfer = queue.saveAs(Endpoint.Local, entry(Endpoint.Local, source.path), chosen.path)
        runBlocking { withTimeout(30_000) { transfer.state.first { it is TransferState.Failed } } }
        queue.resume(transfer)
        awaitDone(transfer)
        assertEquals(listOf(0L, 0L), offsets)
    }

    @Test
    fun aDocumentCancelledBeforeStartingDoesNotStayBehind() {
        val source = localFile("doc-${newId().take(8)}.bin", Random.nextBytes(100))
        val chosen = File(local, "scelto-${newId().take(8)}.bin").apply { writeBytes(ByteArray(0)) }
        val queue = queueOver { real ->
            object : FileSystem by real {
                override suspend fun stat(path: String): RemoteFile? = if (path == source.path) awaitCancellation() else real.stat(path)
            }
        }
        val transfer = queue.saveAs(Endpoint.Local, entry(Endpoint.Local, source.path), chosen.path)
        runBlocking { withTimeout(10_000) { transfer.state.first { it.isActive } } }
        queue.cancel(transfer)
        eventually { !chosen.exists() }
    }

    @Test
    fun anUnknownDateIsNotWrittenOnTheCopy() {
        val source = localFile("nodate-${newId().take(8)}.bin", Random.nextBytes(100))
        val folder = File(local, "dest-${newId().take(8)}").apply { mkdirs() }
        val target = File(folder, "x.bin")
        awaitDone(unknownSizes().saveAs(Endpoint.Local, entry(Endpoint.Local, source.path), target.path))
        assertTrue(target.lastModified() > 1_000_000_000_000L, "date: ${target.lastModified()}")
    }

    @Test
    fun anEmptyDestinationDoesNotFinishAnUnknownSizedSource() {
        val bytes = Random.nextBytes(4_000)
        val name = "resume-${newId().take(8)}.bin"
        val source = localFile(name, bytes)
        val folder = File(local, "dest-${newId().take(8)}").apply { mkdirs() }
        File(folder, name).writeBytes(ByteArray(0))
        awaitDone(unknownSizes().enqueue(Endpoint.Local, listOf(entry(Endpoint.Local, source.path)), Location(Endpoint.Local, folder.path), ConflictPolicy.Resume).single())
        assertTrue(bytes.contentEquals(File(folder, name).readBytes()))
    }

    private fun localFile(name: String, bytes: ByteArray): File = File(local, name).apply { writeBytes(bytes) }

    @Test
    fun parallelTransfersAcrossThreeHostsShareOneQueue() {
        val (basic, bastion, internal) = hosts()
        listOf(basic, bastion, internal).forEach { it.sh("mkdir -p $dir") }
        basic.sh("head -c 6000000 /dev/urandom > $dir/big.bin")
        bastion.sh("mkdir -p $dir/tree/sub && head -c 300000 /dev/urandom > $dir/tree/a.bin && echo ciao > $dir/tree/sub/b.txt && ln -s a.bin $dir/tree/link")
        internal.sh("head -c 2000000 /dev/urandom > $dir/c.bin")
        val upload = localFile("up.bin", Random.nextBytes(1_500_000))

        var mostAtOnce = 0
        scope.launch { transfers.summary.collect { mostAtOnce = maxOf(mostAtOnce, it.active) } }
        transfers.maxParallel.value = 4
        val all = listOf(
            transfers.enqueue(Endpoint.Remote(basic.id), listOf(entry(Endpoint.Remote(basic.id), "$dir/big.bin")), Location(Endpoint.Remote(bastion.id), dir), ConflictPolicy.Overwrite),
            transfers.enqueue(Endpoint.Remote(bastion.id), listOf(entry(Endpoint.Remote(bastion.id), "$dir/tree")), Location(Endpoint.Remote(internal.id), dir), ConflictPolicy.Overwrite),
            transfers.enqueue(Endpoint.Remote(internal.id), listOf(entry(Endpoint.Remote(internal.id), "$dir/c.bin")), Location(Endpoint.Local, local), ConflictPolicy.Overwrite),
            transfers.enqueue(Endpoint.Local, listOf(entry(Endpoint.Local, upload.path.replace('\\', '/'))), Location(Endpoint.Remote(basic.id), dir), ConflictPolicy.Overwrite),
        ).flatten()
        awaitDone(*all.toTypedArray())

        assertTrue(mostAtOnce >= 3, "ran $mostAtOnce at once")
        assertEquals(basic.sh("sha256sum < $dir/big.bin"), bastion.sh("sha256sum < $dir/big.bin"))
        assertEquals(bastion.sh("sha256sum < $dir/tree/a.bin"), internal.sh("sha256sum < $dir/tree/a.bin"))
        assertEquals("ciao", internal.sh("cat $dir/tree/sub/b.txt"))
        assertEquals("", internal.sh("ls $dir/tree | grep link || true"), "symlinks are left out")
        assertEquals(1, all[1].progress.value.skipped)
        assertEquals(internal.sh("sha256sum < $dir/c.bin").substringBefore(' '), sha256(File(local, "c.bin").readBytes()))
        assertEquals(basic.sh("sha256sum < $dir/up.bin").substringBefore(' '), sha256(upload.readBytes()))
        listOf(basic, bastion, internal).forEach { it.sh("rm -rf $dir") }
    }

    @Test
    fun pauseAndResumeContinueFromTheOffset() {
        val (basic, _, _) = hosts()
        basic.sh("mkdir -p $dir && head -c 150000000 /dev/urandom > $dir/huge.bin")
        val transfer = transfers.enqueue(Endpoint.Remote(basic.id), listOf(entry(Endpoint.Remote(basic.id), "$dir/huge.bin")), Location(Endpoint.Local, local), ConflictPolicy.Overwrite).single()

        eventually({ "progress ${transfer.progress.value}" }, timeoutMs = 60_000) { transfer.progress.value.bytesDone > 0 }
        transfers.pause(transfer)
        eventually { transfer.state.value is TransferState.Paused }
        Thread.sleep(400)
        val paused = transfer.progress.value.bytesDone
        Thread.sleep(400)
        assertEquals(paused, transfer.progress.value.bytesDone, "nothing moves while paused")
        val partial = File(local, "huge.bin").length()
        // A write under way when the pause lands reaches the disk without being counted.
        assertTrue(partial - paused in 0..256 * 1024, "the count matches the bytes on disk: $paused, $partial")
        assertTrue(partial in 1 until 150_000_000, "partial file: $partial")

        var lowest = Long.MAX_VALUE
        scope.launch { transfer.progress.drop(1).collect { lowest = minOf(lowest, it.bytesDone) } }
        Thread.sleep(100)
        transfers.resume(transfer)
        awaitDone(transfer)
        assertTrue(lowest >= partial, "resumed from $lowest, the partial file had $partial")
        assertEquals(basic.sh("sha256sum < $dir/huge.bin").substringBefore(' '), sha256(File(local, "huge.bin").readBytes()))
        basic.sh("rm -rf $dir")
        File(local, "huge.bin").delete()
    }

    @Test
    fun conflictPolicies() {
        val (basic, _, _) = hosts()
        val target = Location(Endpoint.Remote(basic.id), dir)
        val file = localFile("same.txt", "new content".encodeToByteArray())
        val source = listOf(entry(Endpoint.Local, file.path.replace('\\', '/')))
        fun runWith(policy: ConflictPolicy) = awaitDone(*transfers.enqueue(Endpoint.Local, source, target, policy).toTypedArray())

        basic.sh("mkdir -p $dir && printf old > $dir/same.txt")
        assertEquals(listOf("same.txt"), runBlocking { transfers.existing(target, listOf("same.txt", "other.txt")) })

        runWith(ConflictPolicy.Skip)
        assertEquals("old", basic.sh("cat $dir/same.txt"))

        runWith(ConflictPolicy.Rename)
        assertEquals("new content", basic.sh("cat '$dir/same (1).txt'"))

        basic.sh("printf new > $dir/same.txt")
        runWith(ConflictPolicy.Resume)
        assertEquals("new content", basic.sh("cat $dir/same.txt"), "the first 3 bytes were kept, the rest appended")

        basic.sh("printf zzzzzzzzzzzzzzzzzzzz > $dir/same.txt")
        runWith(ConflictPolicy.Overwrite)
        assertEquals("new content", basic.sh("cat $dir/same.txt"))
        basic.sh("rm -rf $dir")
    }

    @Test
    fun aDroppedConnectionIsWaitedOut() = TestProxy(TestEnv.BASIC_PORT).use { proxy ->
        val (basic, _, _) = hosts()
        val viaProxy = runBlocking { actions.testHost("via-proxy", proxy.port, services.workspace.workspace.value.identities.first()) }
        basic.sh("mkdir -p $dir && head -c 120000000 /dev/urandom > $dir/big.bin")
        val transfer = transfers.enqueue(Endpoint.Remote(viaProxy.id), listOf(entry(Endpoint.Remote(viaProxy.id), "$dir/big.bin")), Location(Endpoint.Local, local), ConflictPolicy.Overwrite).single()

        eventually({ "progress ${transfer.progress.value}" }, timeoutMs = 60_000) { transfer.progress.value.bytesDone > 0 }
        proxy.up = false
        proxy.cut()
        eventually({ "state ${transfer.state.value}" }) { transfer.state.value is TransferState.Waiting }
        proxy.up = true
        awaitDone(transfer)
        assertEquals(basic.sh("sha256sum < $dir/big.bin").substringBefore(' '), sha256(File(local, "big.bin").readBytes()))
        basic.sh("rm -rf $dir")
        File(local, "big.bin").delete()
    }

    @Test
    fun editorSavesAtomicallyAndNoticesChangesOnTheServer() {
        val (basic, _, _) = hosts()
        val path = "$dir/app.yaml"
        basic.sh("mkdir -p $dir && printf 'porta: 1\\n' > $path && chmod 640 $path")
        val tab = sessions.openEditor(Endpoint.Remote(basic.id), path)
        val editor = runBlocking { withTimeout(30_000) { tab.editor.filterNotNull().first() } }
        assertEquals("porta: 1\n", tab.loaded.value)

        assertEquals(RemoteEditor.SaveResult.Saved, runBlocking { editor.save("porta: 2\n") })
        assertEquals("porta: 2", basic.sh("cat $path"))
        assertEquals("640", basic.sh("stat -c %a $path"), "permissions are kept")
        assertEquals("app.yaml", basic.sh("ls -A $dir"), "no temporary file left behind")

        basic.sh("printf 'porta: 3\\n' > $path && touch -d '+1 minute' $path")
        assertIs<RemoteEditor.SaveResult.ChangedOnServer>(runBlocking { editor.save("porta: 4\n") })
        assertEquals("porta: 3", basic.sh("cat $path"))
        assertEquals(RemoteEditor.SaveResult.Saved, runBlocking { editor.save("porta: 4\n", force = true) })
        assertEquals("porta: 4", basic.sh("cat $path"))
        basic.sh("rm -rf $dir")
    }

    @Test
    fun filePanelBrowsesAndChangesTheServer() {
        val (basic, _, _) = hosts()
        basic.sh("mkdir -p $dir/inside && touch $dir/.nascosto $dir/visibile.txt")
        val browser = sessions.openFiles(basic).browser
        eventually({ "path ${browser.path.value} error ${browser.error.value}" }) { browser.path.value == "/home/dev" }
        browser.open(dir)
        eventually { browser.path.value == dir && !browser.loading.value }
        assertEquals(listOf("inside", "visibile.txt"), browser.visible(browser.entries.value, hidden = false, sort = FileSort()).map { it.name })
        assertEquals(listOf("inside", ".nascosto", "visibile.txt"), browser.visible(browser.entries.value, hidden = true, sort = FileSort()).map { it.name })

        browser.mkdir("nuova")
        eventually({ browser.entries.value.map { it.name }.toString() }) { browser.entries.value.any { it.name == "nuova" } }
        val file = browser.entries.value.first { it.name == "visibile.txt" }
        browser.rename(file, "rinominato.txt")
        eventually { browser.entries.value.any { it.name == "rinominato.txt" } }
        browser.chmod(browser.entries.value.first { it.name == "rinominato.txt" }, "755".toInt(8))
        eventually { basic.sh("stat -c %a $dir/rinominato.txt") == "755" }
        browser.delete(browser.entries.value.filter { it.name == "inside" || it.name == "nuova" })
        eventually({ browser.entries.value.map { it.name }.toString() }) { browser.entries.value.map { it.name } == listOf(".nascosto", "rinominato.txt") }
        assertEquals(null, browser.message.value)
        basic.sh("rm -rf $dir")
    }
}
