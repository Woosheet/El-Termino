package dev.eltermino.app

import dev.eltermino.core.data.Host
import dev.eltermino.core.data.KnownHost
import dev.eltermino.core.data.KnownHostStatus
import dev.eltermino.core.data.knownHostStatus
import dev.eltermino.core.data.trust
import dev.eltermino.core.ssh.HostKeyVerifier
import dev.eltermino.core.ssh.SecretResolver
import dev.eltermino.core.ssh.SshConnection
import dev.eltermino.core.vault.VaultException
import kotlinx.coroutines.CoroutineScope

/**
 * Logs in to hosts and hands out the shared [HostLink]s that terminals, file panels and
 * transfers run on. Whatever a login needs from the user is asked through [prompter].
 */
class Connections(
    private val services: AppServices,
    private val actions: WorkspaceActions,
    scope: CoroutineScope,
    reconnectPolicy: ReconnectPolicy = ReconnectPolicy(),
) {
    val prompter = Prompter()

    private val links = HostLinks(scope, reconnectPolicy, ::openConnection)

    private val mutableLogins = kotlinx.coroutines.flow.MutableSharedFlow<String>(extraBufferCapacity = 16)

    /** The id of every host just logged in to, reconnections included. */
    val logins: kotlinx.coroutines.flow.SharedFlow<String> = mutableLogins

    /** A share of [host]'s link, logging in if nobody has it open yet. Release it when done. */
    suspend fun acquire(host: Host): LinkLease = links.acquire(host)

    /** Logs in to [hostId]; any answer the user asked to keep is saved once the login worked. */
    private suspend fun openConnection(hostId: String): SshConnection {
        val lazySecrets = LazySecrets()
        val afterSuccess = mutableListOf<suspend () -> Unit>()
        try {
            val workspace = services.workspace.workspace.value
            val host = workspace.host(hostId) ?: throw HostMissingException()
            val target = ConnectionPlanner(workspace, prompter, actions, lazySecrets, afterSuccess).plan(host)
            val connection = services.connector.connect(target, hostKeyVerifier(), secretResolver(lazySecrets))
            afterSuccess.forEach { save -> runCatching { save() } }
            markConnected(hostId)
            mutableLogins.tryEmit(hostId)
            return connection
        } finally {
            lazySecrets.clear()
        }
    }

    private fun hostKeyVerifier() = HostKeyVerifier { host, port, fingerprint ->
        val workspace = services.workspace.workspace.value
        when (val status = workspace.knownHostStatus(host, port, fingerprint.keyType, fingerprint.sha256)) {
            KnownHostStatus.Trusted -> true
            else -> {
                val previous = (status as? KnownHostStatus.Changed)?.previous
                val accepted = prompter.ask(PendingPrompt.HostKey(host, port, fingerprint, previous)) == true
                if (accepted) {
                    services.workspace.update {
                        it.trust(KnownHost(host, port, fingerprint.keyType, fingerprint.sha256, fingerprint.publicKey, nowEpochSeconds()))
                    }
                }
                accepted
            }
        }
    }

    private fun secretResolver(lazySecrets: LazySecrets) = SecretResolver { ref ->
        lazySecrets.resolve(ref.id)
            ?: services.vault.get(ref.id)
            ?: throw VaultException("Un segreto di questo host non è più nel vault")
    }

    private suspend fun markConnected(hostId: String) = services.workspace.update { ws ->
        ws.copy(hosts = ws.hosts.map { if (it.id == hostId) it.copy(lastConnectedEpochSeconds = nowEpochSeconds()) else it })
    }
}
