package dev.eltermino.app

import dev.eltermino.core.data.Host
import dev.eltermino.core.data.Identity
import dev.eltermino.core.data.SshKeyEntry
import dev.eltermino.core.data.Workspace
import dev.eltermino.core.ssh.AuthMethod
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class AgentPlanTest {
    private val services = testServices()
    private val host = Host(id = "h", label = "h", address = "h.example.com", identityId = "i")

    private fun workspace(useAgent: Boolean, keyId: String? = null, password: String? = null) = Workspace(
        hosts = listOf(host),
        identities = listOf(Identity(id = "i", label = "i", username = "dev", keyId = keyId, passwordSecretId = password, useAgent = useAgent)),
        keys = keyId?.let { listOf(SshKeyEntry(id = it, label = "k", type = "ssh-ed25519", publicKey = "", fingerprintSha256 = "", privateKeySecretId = "s", encrypted = false)) }.orEmpty(),
    )

    private suspend fun plan(workspace: Workspace, agentSupported: Boolean = true) =
        ConnectionPlanner(workspace, Prompter(), WorkspaceActions(services), LazySecrets(), mutableListOf(), agentSupported).plan(host).auth

    @Test
    fun plannerPutsAgentFirst() = runTest {
        val auth = plan(workspace(useAgent = true, keyId = "k", password = "p"))
        assertIs<AuthMethod.Agent>(auth[0])
        assertIs<AuthMethod.PublicKey>(auth[1])
        assertIs<AuthMethod.Password>(auth[2])
        assertIs<AuthMethod.KeyboardInteractive>(auth[3])
    }

    @Test
    fun agentNeedsNoKey() = runTest {
        // With nothing else saved, a password can still be asked if the agent doesn't do it.
        val auth = plan(workspace(useAgent = true))
        assertEquals(listOf(AuthMethod.Agent::class, AuthMethod.Password::class, AuthMethod.KeyboardInteractive::class), auth.map { it::class })
    }

    @Test
    fun offByDefault() = runTest {
        assertTrue(plan(workspace(useAgent = false, password = "p")).none { it is AuthMethod.Agent })
    }

    @Test
    fun plannerSkipsAgentWhereThereIsNone() = runTest {
        assertFalse(plan(workspace(useAgent = true), agentSupported = false).any { it is AuthMethod.Agent })
    }

    @Test
    fun forwardingFollowsTheHostOnly() = runTest {
        val jump = Host(id = "j", label = "j", address = "j.example.com", identityId = "i", agentForwarding = true)
        val target = host.copy(jumpHostId = "j", agentForwarding = true)
        val workspace = workspace(useAgent = false, password = "p").let { it.copy(hosts = listOf(jump, target)) }
        val plan = ConnectionPlanner(workspace, Prompter(), WorkspaceActions(services), LazySecrets(), mutableListOf(), agentSupported = true).plan(target)
        assertTrue(plan.agentForwarding)
        assertFalse(plan.jumpHosts.single().agentForwarding)
    }

    @Test
    fun noForwardingWhereThereIsNoAgent() = runTest {
        val target = host.copy(agentForwarding = true)
        val plan = ConnectionPlanner(workspace(useAgent = false, password = "p"), Prompter(), WorkspaceActions(services), LazySecrets(), mutableListOf(), agentSupported = false).plan(target)
        assertFalse(plan.agentForwarding)
    }
}
