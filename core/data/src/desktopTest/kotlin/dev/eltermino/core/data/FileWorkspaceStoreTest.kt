package dev.eltermino.core.data

import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class FileWorkspaceStoreTest {
    private val dir = Files.createTempDirectory("workspace").toFile().apply { deleteOnExit() }
    private val file = dir.resolve("workspace.json")

    @Test
    fun startsEmptyAndPersistsAcrossInstances() = runTest {
        val store = FileWorkspaceStore(file)
        assertEquals(Workspace(), store.workspace.value)

        val group = HostGroup(newId(), "Produzione")
        val host = Host(newId(), "web-01", "10.0.0.11", groupId = group.id, tags = listOf("nginx"))
        store.update { it.copy(groups = it.groups + group, hosts = it.hosts + host) }

        val reopened = FileWorkspaceStore(file).workspace.value
        assertEquals(listOf(host), reopened.hosts)
        assertEquals(listOf("Produzione"), reopened.groupPath(host.groupId))
    }

    @Test
    fun keepsPreviousVersionAsBackup() = runTest {
        val store = FileWorkspaceStore(file)
        store.update { it.copy(hosts = listOf(Host(newId(), "a", "a.local"))) }
        store.update { it.copy(hosts = listOf(Host(newId(), "b", "b.local"))) }
        assertTrue(dir.resolve("workspace.json.bak").readText().contains("a.local"))
        assertTrue(file.readText().contains("b.local"))
    }

    @Test
    fun ignoresFieldsFromNewerMinorChanges() = runTest {
        file.writeText("""{"version":1,"hosts":[{"id":"1","label":"x","address":"x.local","futureField":true}]}""")
        assertEquals("x.local", FileWorkspaceStore(file).workspace.value.hosts.single().address)
    }

    @Test
    fun knownHostStatus() {
        val known = KnownHost("web-01", 22, "ssh-ed25519", "SHA256:aaa", "ssh-ed25519 AAAA", 0)
        val ws = Workspace().trust(known)
        assertEquals(KnownHostStatus.Trusted, ws.knownHostStatus("WEB-01", 22, "ssh-ed25519", "SHA256:aaa"))
        assertEquals(KnownHostStatus.Unknown, ws.knownHostStatus("web-01", 2222, "ssh-ed25519", "SHA256:aaa"))
        assertEquals(KnownHostStatus.Unknown, ws.knownHostStatus("web-01", 22, "ssh-rsa", "SHA256:bbb"))
        assertIs<KnownHostStatus.Changed>(ws.knownHostStatus("web-01", 22, "ssh-ed25519", "SHA256:bbb"))

        val replaced = ws.trust(known.copy(fingerprintSha256 = "SHA256:bbb"))
        assertEquals(1, replaced.knownHosts.size)
    }

    @Test
    fun jumpChainOutermostFirstAndCycleSafe() {
        val bastion = Host("b", "bastion", "bastion.example")
        val inner = Host("i", "inner", "10.0.0.2", jumpHostId = "m")
        val middle = Host("m", "middle", "10.0.0.1", jumpHostId = "b")
        val ws = Workspace(hosts = listOf(bastion, inner, middle))
        assertEquals(listOf("bastion", "middle"), ws.jumpChain(inner).map { it.label })

        val loop = Workspace(hosts = listOf(Host("x", "x", "x", jumpHostId = "y"), Host("y", "y", "y", jumpHostId = "x")))
        assertEquals(listOf("y"), loop.jumpChain(loop.hosts.first()).map { it.label })
    }
}
