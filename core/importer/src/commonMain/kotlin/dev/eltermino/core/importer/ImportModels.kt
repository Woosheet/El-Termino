package dev.eltermino.core.importer

/**
 * A host read from a foreign format, before the app turns it into its own models.
 * Nothing here is resolved: [identityFiles] keep `~`, and a [JumpTarget.host] may be
 * the alias of another imported host rather than an address.
 */
data class ImportedHost(
    val label: String,
    val address: String,
    /** Null when the source doesn't say, meaning the SSH default 22. */
    val port: Int? = null,
    val username: String? = null,
    /**
     * Plain-text password when the source carries one (Termius CSV can). The app
     * should move it into the vault right away; [toString] never prints it.
     */
    val password: String? = null,
    /** Outermost group first, for example `["Produzione", "Web"]`. */
    val groupPath: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
    val identityFiles: List<String> = emptyList(),
    val certificateFiles: List<String> = emptyList(),
    /** Hops to go through, outermost first, as with OpenSSH `ProxyJump`. */
    val proxyJump: List<JumpTarget> = emptyList(),
    val notes: String? = null,
    /** A private key carried in the source itself, as Termius's local data does. */
    val key: ImportedKey? = null,
) {
    override fun toString(): String =
        "ImportedHost(label=$label, address=$address, port=$port, username=$username, " +
            "password=${if (password == null) "null" else "***"}, groupPath=$groupPath, tags=$tags, " +
            "identityFiles=$identityFiles, certificateFiles=$certificateFiles, proxyJump=$proxyJump, notes=$notes, key=$key)"
}

/** A private key's contents, with its passphrase if the source keeps one. Never printed. */
class ImportedKey(val label: String, val privateKey: String, val passphrase: String? = null) {
    override fun toString() = "ImportedKey(label=$label, ***)"
}

data class JumpTarget(
    val username: String?,
    /** An address, or the alias of another host in the same import. */
    val host: String,
    val port: Int?,
)

/**
 * Something the import skipped or couldn't carry over. [message] is in Italian, ready
 * to show in the import preview.
 */
data class ImportWarning(
    /** 1-based line in [file], when the problem has a position. */
    val line: Int?,
    val message: String,
    /** The included file the line belongs to; null for the main input. */
    val file: String? = null,
)

data class ImportResult(
    val hosts: List<ImportedHost>,
    val warnings: List<ImportWarning>,
)

/** A file returned by the `Include` resolver of [SshConfigParser]. */
data class IncludedFile(
    /** Shown in warnings, for example `~/.ssh/config.d/work`. */
    val name: String,
    val text: String,
)
