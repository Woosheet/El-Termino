package dev.eltermino.app

import dev.eltermino.core.ssh.RemoteFile
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ExternalEditFlowTest {
    private val dir = Files.createTempDirectory("remote").toFile().apply { deleteOnExit() }
    private val remote = File(dir, "nginx.conf").apply { writeText("uno\n") }
    private val fs = LocalFileSystem(dir)

    private class FakeFile : ExternalFile {
        val changes = Channel<ByteArray?>(Channel.UNLIMITED)
        var closed = false
        override val path = "/tmp/x"
        override suspend fun awaitChange(): ByteArray? = changes.receive()
        override fun finish() {}
        override fun close() { closed = true }
    }

    @Test
    fun savesUploadOnChange() {
        val file = FakeFile()
        var uploads = 0
        file.changes.trySend("due\n".encodeToByteArray())
        file.changes.trySend(null)
        val editor = RemoteEditor(Endpoint.Local, remote.path, fs)
        runBlocking {
            editor.load()
            followExternalFile(editor, file, { false }) { uploads++ }
        }
        assertEquals("due\n", remote.readText())
        assertEquals(1, uploads)
        assertTrue(file.closed)
    }

    @Test
    fun remoteChangeAsksBeforeOverwrite() {
        val file = FakeFile()
        val editor = RemoteEditor(Endpoint.Local, remote.path, fs)
        var asked = 0
        runBlocking {
            editor.load()
            remote.writeText("cambiato sul server, più lungo\n")
            file.changes.trySend("mio\n".encodeToByteArray())
            file.changes.trySend(null)
            followExternalFile(editor, file, { asked++; false }) {}
        }
        assertEquals(1, asked)
        assertEquals("cambiato sul server, più lungo\n", remote.readText())
    }

    @Test
    fun answeringYesOverwrites() {
        val file = FakeFile()
        val editor = RemoteEditor(Endpoint.Local, remote.path, fs)
        runBlocking {
            editor.load()
            remote.writeText("cambiato sul server, più lungo\n")
            file.changes.trySend("mio\n".encodeToByteArray())
            file.changes.trySend(null)
            followExternalFile(editor, file, { true }) {}
        }
        assertEquals("mio\n", remote.readText())
    }

    @Test
    fun atomicSaveKeepsPermissions() {
        Files.setPosixFilePermissions(remote.toPath(), PosixFilePermissions.fromString("rw-r-----"))
        val file = FakeFile()
        file.changes.trySend("due\n".encodeToByteArray())
        file.changes.trySend(null)
        val editor = RemoteEditor(Endpoint.Local, remote.path, fs)
        runBlocking {
            editor.load()
            followExternalFile(editor, file, { false }) {}
        }
        assertEquals("rw-r-----", PosixFilePermissions.toString(Files.getPosixFilePermissions(remote.toPath())))
        assertEquals(listOf("nginx.conf"), dir.list()!!.toList())
    }

    @Test
    fun anyBytesSurviveTheRoundTrip() {
        val bytes = byteArrayOf(0, -1, -2, 65)
        remote.writeBytes(bytes)
        val file = FakeFile()
        val editor = RemoteEditor(Endpoint.Local, remote.path, fs)
        runBlocking {
            assertContentEquals(bytes, editor.loadBytes())
            file.changes.trySend(bytes + 66)
            file.changes.trySend(null)
            followExternalFile(editor, file, { false }) {}
        }
        assertContentEquals(bytes + 66, remote.readBytes())
    }

    @Test
    fun failedUploadIsReportedAndTheNextSaveGoesThrough() {
        val file = FakeFile()
        val editor = RemoteEditor(Endpoint.Local, "${dir.path}/nessuna/cartella", fs)
        val errors = mutableListOf<Throwable>()
        file.changes.trySend("due".encodeToByteArray())
        file.changes.trySend(null)
        runBlocking { followExternalFile(editor, file, { false }, { errors += it }) {} }
        assertEquals(1, errors.size)
    }

    @Test
    fun finishUploadsASaveNotSeenYet() {
        val file = DesktopExternalFile("a.txt", "uno".encodeToByteArray(), pollMillis = 60_000)
        File(file.path).writeText("due")
        file.finish()
        assertEquals("due", waitForChange(file))
        assertNull(waitForChange(file))
        file.close()
    }

    @Test
    fun aNameThatLeavesTheFolderIsRefused() {
        assertFailsWith<IllegalArgumentException> { DesktopExternalFile("..", ByteArray(0)) }
    }

    private fun waitForChange(file: DesktopExternalFile) = runBlocking { withTimeout(5_000) { file.awaitChange()?.decodeToString() } }

    @Test
    fun tempFilePrivateAndRemovedOnClose() {
        val file = DesktopExternalFile("nginx.conf", "uno\n".encodeToByteArray(), pollMillis = 20)
        val path = File(file.path)
        assertEquals("nginx.conf", path.name)
        assertEquals("uno\n", path.readText())
        val permissions = Files.getPosixFilePermissions(path.parentFile.toPath())
        assertEquals(setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE), permissions)
        assertEquals(setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE), Files.getPosixFilePermissions(path.toPath()))
        file.close()
        assertFalse(path.parentFile.exists())
    }

    @Test
    fun seesAPlainSave() {
        val file = DesktopExternalFile("a.txt", "uno".encodeToByteArray(), pollMillis = 20)
        File(file.path).writeText("due, più lungo")
        assertEquals("due, più lungo", waitForChange(file))
        file.close()
    }

    @Test
    fun editorSaveViaRenameStillSeen() {
        val file = DesktopExternalFile("a.txt", "uno".encodeToByteArray(), pollMillis = 20)
        val temp = File(File(file.path).parentFile, "a.txt.swp").apply { writeText("due") }
        Files.move(temp.toPath(), File(file.path).toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        assertEquals("due", waitForChange(file))
        file.close()
    }

    @Test
    fun deletedFileEndsTheWatch() {
        val file = DesktopExternalFile("a.txt", "uno".encodeToByteArray(), pollMillis = 20)
        // Some editors replace the file: only a file gone for a while counts.
        File(file.path).delete()
        assertNull(waitForChange(file))
        file.close()
    }

    private fun entry(name: String, size: Long, directory: Boolean = false) = RemoteFile(
        name = name, path = "/$name", size = size,
        type = if (directory) RemoteFile.Type.Directory else RemoteFile.Type.File,
        permissions = 0, modifiedEpochSeconds = 0,
    )

    @Test
    fun dragOverLimitRefused() {
        assertNull(dragOutProblem(listOf(entry("a", DRAG_OUT_LIMIT_BYTES))))
        assertNotNull(dragOutProblem(listOf(entry("a", DRAG_OUT_LIMIT_BYTES), entry("b", 1))))
    }

    @Test
    fun folderDragRefused() {
        assertNotNull(dragOutProblem(listOf(entry("cartella", 0, directory = true))))
    }

    @Test
    fun dragDownloadsTheFiles() {
        val target = Files.createTempDirectory("drag").toFile().apply { deleteOnExit() }
        val files = runBlocking { downloadForDrag(fs, listOf(remote.path), target) }
        assertEquals("uno\n", files.single().readText())
        assertEquals("nginx.conf", files.single().name)
    }

    @Test
    fun dragRefusesNamesThatLeaveTheFolder() {
        val target = Files.createTempDirectory("drag").toFile().apply { deleteOnExit() }
        assertFailsWith<IllegalArgumentException> { runBlocking { downloadForDrag(fs, listOf("/x/.."), target) } }
    }
}
