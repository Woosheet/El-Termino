package dev.eltermino.app

import dev.eltermino.core.data.Host
import dev.eltermino.core.data.Identity
import dev.eltermino.core.data.SshKeyEntry
import dev.eltermino.core.data.Workspace
import dev.eltermino.core.data.jumpChain
import dev.eltermino.core.data.newId
import dev.eltermino.core.ssh.AuthMethod
import dev.eltermino.core.ssh.ConnectTarget
import dev.eltermino.core.ssh.KeyboardInteractiveResponder
import dev.eltermino.core.ssh.SecretRef

/**
 * Turns a host and its jump chain into a [ConnectTarget]. A missing username is asked
 * right away; a missing password or passphrase becomes a [LazySecrets] entry, asked
 * only when the connector reads it, which is after the host key has been verified.
 * Answers the user asked to keep are saved by [afterSuccess] once the login worked.
 */
internal class ConnectionPlanner(
    private val workspace: Workspace,
    private val prompter: Prompter,
    private val actions: WorkspaceActions,
    private val lazySecrets: LazySecrets,
    private val afterSuccess: MutableList<suspend () -> Unit>,
    private val agentSupported: Boolean = false,
) {
    suspend fun plan(host: Host): ConnectTarget =
        target(host).copy(jumpHosts = workspace.jumpChain(host).map { target(it) })

    private suspend fun target(host: Host): ConnectTarget {
        val identity = workspace.identity(host.identityId)
        val username = host.username?.takeIf { it.isNotBlank() }
            ?: identity?.username
            ?: prompter.ask(PendingPrompt.Text("Utente per ${host.label}", "Nome utente"))
            ?: throw PromptCancelledException()

        val auth = mutableListOf<AuthMethod>()
        if (identity?.useAgent == true && agentSupported) auth += AuthMethod.Agent
        val key = workspace.key(identity?.keyId)
        if (key != null) auth += AuthMethod.PublicKey(SecretRef(key.privateKeySecretId), passphrase(key), key.certificate)

        val savedPassword = identity?.passwordSecretId
        when {
            savedPassword != null -> auth += AuthMethod.Password(SecretRef(savedPassword))
            key == null -> auth += AuthMethod.Password(
                askLater(PendingPrompt.Secret("Password per $username@${host.address}", "Password", allowSave = true)) { value ->
                    actions.savePassword(host.id, identity, username, value)
                },
            )
        }
        // Servers with OTP or other PAM questions only speak keyboard-interactive.
        auth += AuthMethod.KeyboardInteractive(interactive(host))

        return ConnectTarget(host = host.address, port = host.port, username = username, auth = auth)
    }

    private suspend fun passphrase(key: SshKeyEntry): SecretRef? {
        val saved = key.passphraseSecretId
        return when {
            !key.encrypted -> null
            saved != null -> SecretRef(saved)
            else -> askLater(PendingPrompt.Secret("Passphrase della chiave ${key.label}", "Passphrase", allowSave = true)) { value ->
                actions.savePassphrase(key.id, value)
            }
        }
    }

    private fun interactive(host: Host) = KeyboardInteractiveResponder { name, instruction, prompts ->
        if (prompts.isEmpty()) return@KeyboardInteractiveResponder emptyList()
        prompter.ask(PendingPrompt.Interactive(host.label, name, instruction, prompts))
            ?: throw PromptCancelledException()
    }

    private fun askLater(prompt: PendingPrompt.Secret, save: suspend (ByteArray) -> Unit): SecretRef {
        val id = "prompt:${newId()}"
        lazySecrets.register(id) {
            val answer = prompter.ask(prompt) ?: throw PromptCancelledException()
            if (answer.save) afterSuccess += { save(answer.value) }
            answer.value
        }
        return SecretRef(id)
    }
}

/**
 * Secrets of one connection attempt that come from the user instead of the vault. Each
 * is asked at most once, and all are wiped by [clear].
 */
internal class LazySecrets {
    private val askers = mutableMapOf<String, suspend () -> ByteArray>()
    private val answers = mutableMapOf<String, ByteArray>()

    fun register(id: String, ask: suspend () -> ByteArray) {
        askers[id] = ask
    }

    /** Null when [id] isn't one of ours, so the caller falls back to the vault. */
    suspend fun resolve(id: String): ByteArray? {
        answers[id]?.let { return it.copyOf() }
        val ask = askers[id] ?: return null
        return ask().also { answers[id] = it }.copyOf()
    }

    fun clear() {
        answers.values.forEach { it.fill(0) }
        answers.clear()
        askers.clear()
    }
}

/** The username a host will log in with, if it's known without asking. */
fun Workspace.usernameFor(host: Host): String? =
    host.username?.takeIf { it.isNotBlank() } ?: identity(host.identityId)?.username

internal fun Identity.describe(workspace: Workspace): String {
    val key = workspace.key(keyId)
    return listOfNotNull(
        username,
        key?.let { "chiave ${it.label}" },
        if (passwordSecretId != null) "password salvata" else null,
    ).joinToString(" · ")
}
