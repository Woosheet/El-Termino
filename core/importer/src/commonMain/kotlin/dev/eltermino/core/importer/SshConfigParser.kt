package dev.eltermino.core.importer

/**
 * Reads an OpenSSH client config (`~/.ssh/config`) the way `ssh` evaluates it: for
 * every concrete alias on a `Host` line, the options of all matching blocks are
 * collected in file order and the first value obtained wins. Wildcard and negated
 * patterns never become hosts; they only provide defaults.
 */
object SshConfigParser {
    // Same limit as OpenSSH's READCONF_MAX_DEPTH.
    private const val MAX_INCLUDE_DEPTH = 16

    /**
     * @param include resolves one `Include` pattern to the files it names. Patterns are
     *   passed as written: the caller expands `~`, makes relative paths relative to
     *   `~/.ssh` and applies globs.
     */
    fun parse(text: String, include: (pattern: String) -> List<IncludedFile> = { emptyList() }): ImportResult {
        val warnings = Warnings()
        val directives = mutableListOf<Directive>()
        val aliases = Aliases()
        read(text, file = null, depth = 0, initialBlock = Block.Global, include, Output(directives, aliases, warnings))

        val hosts = aliases.inOrder().map { evaluate(it, directives, warnings) }
        return ImportResult(hosts, warnings.list)
    }

    private sealed interface Block {
        data object Global : Block
        data class Host(val patterns: List<String>) : Block
        /** Inside a `Match` block, which we don't evaluate. */
        data object Skipped : Block
    }

    private class Directive(
        val keyword: String,
        val args: List<String>,
        val block: Block,
        val line: Int,
        val file: String?,
    )

    /**
     * Concrete names from `Host` lines in order of first appearance. Host lines without
     * options still name hosts, so they're collected here rather than from directives.
     * OpenSSH matches aliases case-insensitively, so `Web` and `web` are one host.
     */
    private class Aliases {
        private val byKey = LinkedHashMap<String, String>()

        fun add(alias: String) {
            byKey.getOrPut(alias.lowercase()) { alias }
        }

        fun inOrder(): List<String> = byKey.values.toList()
    }

    private class Output(val directives: MutableList<Directive>, val aliases: Aliases, val warnings: Warnings)

    private class Warnings {
        private val seen = mutableSetOf<ImportWarning>()
        val list = mutableListOf<ImportWarning>()

        fun add(line: Int?, file: String?, message: String) {
            val warning = ImportWarning(line, message, file)
            if (seen.add(warning)) list += warning
        }
    }

    private fun read(
        text: String,
        file: String?,
        depth: Int,
        initialBlock: Block,
        include: (String) -> List<IncludedFile>,
        out: Output,
    ) {
        val warnings = out.warnings
        // Like OpenSSH, an Include inside a Host block reads the file in that block's
        // context, and the enclosing file goes back to it afterwards.
        var block = initialBlock
        text.lineSequence().forEachIndexed { index, raw ->
            val lineNumber = index + 1
            val line = raw.trim().removeSuffix("\r").trim()
            if (line.isEmpty() || line.startsWith("#")) return@forEachIndexed

            val (keyword, rest) = splitKeyword(line)
            val args = tokenize(rest) ?: run {
                warnings.add(lineNumber, file, "Virgolette non chiuse: riga ignorata")
                return@forEachIndexed
            }

            when (keyword) {
                "host" -> {
                    if (args.isEmpty()) {
                        warnings.add(lineNumber, file, "Riga Host senza nomi: ignorata")
                        block = Block.Skipped
                    } else {
                        block = Block.Host(args)
                        args.filter(::isConcrete).forEach(out.aliases::add)
                    }
                }

                "match" -> {
                    warnings.add(lineNumber, file, "Blocco Match ignorato: le condizioni Match non vengono importate")
                    block = Block.Skipped
                }

                "include" -> {
                    if (block == Block.Skipped) return@forEachIndexed
                    if (depth + 1 > MAX_INCLUDE_DEPTH) {
                        warnings.add(lineNumber, file, "Troppi Include annidati: ${args.joinToString(" ")} non letto")
                        return@forEachIndexed
                    }
                    for (pattern in args) {
                        val files = include(pattern)
                        if (files.isEmpty()) {
                            warnings.add(lineNumber, file, "Include non risolto: $pattern")
                        }
                        for (included in files) {
                            read(included.text, included.name, depth + 1, block, include, out)
                        }
                    }
                }

                else -> out.directives += Directive(keyword, args, block, lineNumber, file)
            }
        }
    }

    /** `Keyword value`, `Keyword=value` and `Keyword = value` are all valid. */
    private fun splitKeyword(line: String): Pair<String, String> {
        val end = line.indexOfFirst { it.isWhitespace() || it == '=' }.let { if (it < 0) line.length else it }
        val keyword = line.substring(0, end).lowercase()
        var rest = line.substring(end).trimStart()
        if (rest.startsWith("=")) rest = rest.substring(1).trimStart()
        return keyword to rest
    }

    /**
     * Splits on whitespace, keeping double-quoted parts together. An unquoted `#` at the
     * start of a word ends the line, as in OpenSSH 8.7 and later. Null if a quote is
     * left open.
     */
    private fun tokenize(text: String): List<String>? {
        val tokens = mutableListOf<String>()
        val current = StringBuilder()
        var inQuotes = false
        var hasToken = false
        for (c in text) {
            when {
                c == '#' && !inQuotes && !hasToken -> break
                c == '"' -> {
                    inQuotes = !inQuotes
                    hasToken = true
                }
                c.isWhitespace() && !inQuotes -> {
                    if (hasToken) tokens += current.toString()
                    current.clear()
                    hasToken = false
                }
                else -> {
                    current.append(c)
                    hasToken = true
                }
            }
        }
        if (inQuotes) return null
        if (hasToken) tokens += current.toString()
        return tokens
    }

    private fun isConcrete(pattern: String) =
        !pattern.startsWith("!") && '*' !in pattern && '?' !in pattern

    private fun matchesBlock(block: Block, alias: String): Boolean = when (block) {
        Block.Global -> true
        Block.Skipped -> false
        is Block.Host -> {
            var matched = false
            for (pattern in block.patterns) {
                if (pattern.startsWith("!")) {
                    if (glob(alias, pattern.substring(1))) return false
                } else if (glob(alias, pattern)) {
                    matched = true
                }
            }
            matched
        }
    }

    private fun evaluate(alias: String, directives: List<Directive>, warnings: Warnings): ImportedHost {
        var hostName: String? = null
        var port: Int? = null
        var user: String? = null
        val identityFiles = mutableListOf<String>()
        val certificateFiles = mutableListOf<String>()
        var proxyDecided = false
        var proxyJump: List<JumpTarget> = emptyList()
        var forwards = false

        for (d in directives) {
            if (!matchesBlock(d.block, alias)) continue
            val value = d.args.firstOrNull()
            when (d.keyword) {
                "hostname" -> if (hostName == null && value != null) hostName = value
                "port" -> if (port == null && value != null) {
                    val parsed = value.toIntOrNull()?.takeIf { it in 1..65535 }
                    if (parsed == null) warnings.add(d.line, d.file, "Porta non valida: $value") else port = parsed
                }
                "user" -> if (user == null && value != null) user = value
                "identityfile" -> if (value != null) identityFiles += value
                "certificatefile" -> if (value != null) certificateFiles += value
                "proxyjump" -> if (!proxyDecided && value != null) {
                    proxyDecided = true
                    if (!value.equals("none", ignoreCase = true)) {
                        proxyJump = value.split(',').mapNotNull { spec ->
                            parseJump(spec).also {
                                if (it == null) warnings.add(d.line, d.file, "ProxyJump non valido: $spec")
                            }
                        }
                    }
                }
                "proxycommand" -> if (!proxyDecided && value != null) {
                    proxyDecided = true
                    if (!value.equals("none", ignoreCase = true)) {
                        warnings.add(d.line, d.file, "ProxyCommand non è supportato: $alias è importato senza proxy")
                    }
                }
                "localforward", "remoteforward", "dynamicforward" -> if (!forwards) {
                    forwards = true
                    warnings.add(d.line, d.file, "Le regole di port forwarding di $alias saranno importate in una fase successiva")
                }
            }
        }

        val address = expandHostName(hostName ?: alias, alias)
        val tokens = mapOf('h' to address, 'n' to alias, 'r' to (user ?: ""), 'p' to (port ?: 22).toString(), 'd' to "~")
        return ImportedHost(
            label = alias,
            address = address,
            port = port,
            username = user,
            identityFiles = identityFiles.map { expandTokens(it, tokens) }.distinct(),
            certificateFiles = certificateFiles.map { expandTokens(it, tokens) }.distinct(),
            proxyJump = proxyJump,
        )
    }

    /** HostName only accepts `%h` (the alias) and `%%`. */
    private fun expandHostName(value: String, alias: String) = expandTokens(value, mapOf('h' to alias))

    /** Replaces the `%x` tokens we know; unknown ones (local user, local host…) stay as written. */
    private fun expandTokens(value: String, tokens: Map<Char, String>): String {
        if ('%' !in value) return value
        val out = StringBuilder()
        var i = 0
        while (i < value.length) {
            val c = value[i]
            if (c == '%' && i + 1 < value.length) {
                val next = value[i + 1]
                val replacement = if (next == '%') "%" else tokens[next]
                if (replacement != null) {
                    out.append(replacement)
                    i += 2
                    continue
                }
            }
            out.append(c)
            i++
        }
        return out.toString()
    }

    /** `[user@]host[:port]`, `[user@][ipv6]:port` or `ssh://[user@]host[:port]`. */
    internal fun parseJump(spec: String): JumpTarget? {
        var s = spec.trim()
        if (s.startsWith("ssh://")) s = s.removePrefix("ssh://").trimEnd('/')
        if (s.isEmpty()) return null

        val at = s.lastIndexOf('@')
        val user = if (at >= 0) s.substring(0, at).ifEmpty { null } else null
        val hostPort = if (at >= 0) s.substring(at + 1) else s

        val host: String
        var portText: String? = null
        if (hostPort.startsWith("[")) {
            val close = hostPort.indexOf(']')
            if (close < 0) return null
            host = hostPort.substring(1, close)
            val after = hostPort.substring(close + 1)
            if (after.isNotEmpty()) {
                if (!after.startsWith(":")) return null
                portText = after.substring(1)
            }
        } else if (hostPort.count { it == ':' } == 1) {
            host = hostPort.substringBefore(':')
            portText = hostPort.substringAfter(':')
        } else {
            // No colon, or a bare IPv6 address without a port.
            host = hostPort
        }
        if (host.isEmpty() || host.any { it.isWhitespace() }) return null
        val port = portText?.let { text -> text.toIntOrNull()?.takeIf { it in 1..65535 } ?: return null }
        return JumpTarget(user, host, port)
    }

    /** OpenSSH pattern match: `*` and `?`, case-insensitive. */
    internal fun glob(text: String, pattern: String): Boolean {
        val t = text.lowercase()
        val p = pattern.lowercase()
        var ti = 0
        var pi = 0
        var star = -1
        var mark = 0
        while (ti < t.length) {
            when {
                pi < p.length && (p[pi] == '?' || p[pi] == t[ti]) -> { ti++; pi++ }
                pi < p.length && p[pi] == '*' -> { star = pi++; mark = ti }
                star >= 0 -> { pi = star + 1; ti = ++mark }
                else -> return false
            }
        }
        while (pi < p.length && p[pi] == '*') pi++
        return pi == p.length
    }
}
