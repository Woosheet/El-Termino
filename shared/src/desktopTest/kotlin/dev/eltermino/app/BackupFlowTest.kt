package dev.eltermino.app

import dev.eltermino.core.data.FileWorkspaceStore
import dev.eltermino.core.data.Host
import dev.eltermino.core.data.KnownHost
import dev.eltermino.core.data.SavedLayout
import dev.eltermino.core.data.SavedPane
import dev.eltermino.core.data.SavedTab
import dev.eltermino.core.data.UiSettings
import dev.eltermino.core.data.Workspace
import dev.eltermino.core.data.mergedWith
import dev.eltermino.core.data.WorkspaceStore
import dev.eltermino.core.vault.BackupCipher
import dev.eltermino.core.vault.EncryptedFileVault
import dev.eltermino.core.vault.MasterKeyProvider
import dev.eltermino.core.vault.VaultException
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BackupFlowTest {
    private val password = "password di prova".toCharArray()

    private class Device {
        val dir = Files.createTempDirectory("backup").toFile().apply { deleteOnExit() }
        val store = FileWorkspaceStore(dir.resolve("workspace.json"))
        val key = Random.nextBytes(32)
        val vault = EncryptedFileVault(dir.resolve("vault.bin"), MasterKeyProvider { key.copyOf() })
        val safetyCopies = mutableListOf<ByteArray>()
        val service = BackupService(store, vault) { safetyCopies += it }
    }

    private fun host(id: String, label: String = id) = Host(id = id, label = label, address = "$id.example.com")
    private fun pin(key: String) = KnownHost(host = "a.example.com", port = 22, keyType = "ssh-ed25519", fingerprintSha256 = key, publicKey = key, addedAtEpochSeconds = 0)
    private fun layout(hostId: String) = SavedLayout(id = "l", name = "ultima", tabs = listOf(SavedTab(layout = "Single", panes = listOf(SavedPane(kind = "terminal", hostId = hostId)))))

    @Test
    fun exportLeavesOutDeviceSettings() = runTest {
        val device = Device()
        device.store.update { it.copy(hosts = listOf(host("a")), ui = UiSettings(theme = "dracula"), lastSession = layout("a")) }
        val plain = BackupCipher.open(device.service.export(password), password).decodeToString()
        assertTrue("a.example.com" in plain)
        assertFalse("dracula" in plain)
        assertFalse("lastSession" in plain)
    }

    @Test
    fun secretsTravelBetweenDevices() = runTest {
        val mac = Device()
        val secretId = mac.vault.add("segreto".encodeToByteArray())
        val phone = Device()
        val content = phone.service.read(mac.service.export(password), password)
        phone.service.restore(content, replaceAll = false, password)
        assertContentEquals("segreto".encodeToByteArray(), phone.vault.get(secretId))
    }

    @Test
    fun mergeBackupWinsOnSameId() {
        val merged = Workspace(hosts = listOf(host("a", "locale"))).mergedWith(Workspace(hosts = listOf(host("a", "backup"))))
        assertEquals(listOf("backup"), merged.hosts.map { it.label })
    }

    @Test
    fun mergeKeepsLocalOnlyItems() {
        val merged = Workspace(hosts = listOf(host("a"))).mergedWith(Workspace(hosts = listOf(host("b"))))
        assertEquals(setOf("a", "b"), merged.hosts.map { it.id }.toSet())
    }

    @Test
    fun mergeKeepsLocalKnownHostOnConflict() = runTest {
        val local = Workspace(knownHosts = listOf(pin("locale")))
        val backup = Workspace(knownHosts = listOf(pin("backup"), pin("x").copy(host = "b.example.com")))
        assertEquals(setOf("locale", "x"), local.mergedWith(backup).knownHosts.map { it.fingerprintSha256 }.toSet())

        val device = Device()
        device.store.update { local }
        val other = Device()
        other.store.update { backup }
        val preview = device.service.preview(device.service.read(other.service.export(password), password))
        assertEquals(listOf("backup"), preview.knownHostConflicts.map { it.fingerprintSha256 })
    }

    @Test
    fun replaceKeepsDeviceSettings() = runTest {
        val source = Device().also { it.store.update { w -> w.copy(hosts = listOf(host("a"))) } }
        val device = Device()
        device.store.update { it.copy(hosts = listOf(host("old")), ui = UiSettings(theme = "nord"), lastSession = layout("a")) }
        device.service.restore(device.service.read(source.service.export(password), password), replaceAll = true, password)
        val after = device.store.workspace.value
        assertEquals(listOf("a"), after.hosts.map { it.id })
        assertEquals("nord", after.ui.theme)
        assertNotNull(after.lastSession)
    }

    @Test
    fun replaceDropsLastSessionOfVanishedHosts() = runTest {
        val source = Device().also { it.store.update { w -> w.copy(hosts = listOf(host("a"))) } }
        val device = Device()
        device.store.update { it.copy(hosts = listOf(host("old")), lastSession = layout("old")) }
        device.service.restore(device.service.read(source.service.export(password), password), replaceAll = true, password)
        assertNull(device.store.workspace.value.lastSession)
    }

    @Test
    fun replaceRemovesOtherSecrets() = runTest {
        val source = Device()
        val device = Device()
        val old = device.vault.add(byteArrayOf(1))
        device.service.restore(device.service.read(source.service.export(password), password), replaceAll = true, password)
        assertNull(device.vault.get(old))
    }

    @Test
    fun restoreSavesSafetyCopyFirst() = runTest {
        val source = Device().also { it.store.update { w -> w.copy(hosts = listOf(host("new"))) } }
        val device = Device()
        device.store.update { it.copy(hosts = listOf(host("old"))) }
        val secret = device.vault.add("vecchio".encodeToByteArray())
        val service = BackupService(device.store, device.vault) { copy ->
            // The copy must show the data as it was, readable with the password just typed.
            assertEquals(listOf("old"), device.store.workspace.value.hosts.map { it.id })
            device.safetyCopies += copy
        }
        service.restore(service.read(source.service.export(password), password), replaceAll = true, password)
        val copy = service.read(device.safetyCopies.single(), password)
        assertEquals(listOf("old"), copy.workspace.hosts.map { it.id })
        assertTrue(secret in copy.secrets)
    }

    @Test
    fun wrongPasswordChangesNothing() = runTest {
        val device = Device()
        device.store.update { it.copy(hosts = listOf(host("a"))) }
        val file = device.service.export(password)
        assertFailsWith<VaultException> { device.service.read(file, "sbagliata".toCharArray()) }
        assertEquals(listOf("a"), device.store.workspace.value.hosts.map { it.id })
    }

    @Test
    fun newerFormatRefused() = runTest {
        val device = Device()
        val json = """{"formatVersion":99,"workspace":{},"secrets":{}}"""
        val file = BackupCipher.seal(json.encodeToByteArray(), password)
        assertFailsWith<VaultException> { device.service.read(file, password) }
    }

    @Test
    fun newerWorkspaceRefused() = runTest {
        val device = Device()
        val json = """{"formatVersion":1,"workspace":{"version":99},"secrets":{}}"""
        val file = BackupCipher.seal(json.encodeToByteArray(), password)
        assertFailsWith<VaultException> { device.service.read(file, password) }
    }

    @Test
    fun notJsonRefused() = runTest {
        val device = Device()
        val file = BackupCipher.seal("non json".encodeToByteArray(), password)
        assertFailsWith<VaultException> { device.service.read(file, password) }
    }

    @Test
    fun failureWhileWritingWorkspaceRestoresVault() = runTest {
        val source = Device()
        val incoming = source.vault.add("nuovo".encodeToByteArray())
        val device = Device()
        val old = device.vault.add("vecchio".encodeToByteArray())
        val broken = object : WorkspaceStore by device.store {
            override suspend fun update(transform: (Workspace) -> Workspace): Workspace = error("disco pieno")
        }
        val service = BackupService(broken, device.vault) {}
        val content = service.read(source.service.export(password), password)
        assertFailsWith<IllegalStateException> { service.restore(content, replaceAll = true, password) }
        assertNull(device.vault.get(incoming))
        assertContentEquals("vecchio".encodeToByteArray(), device.vault.get(old))
    }
}
