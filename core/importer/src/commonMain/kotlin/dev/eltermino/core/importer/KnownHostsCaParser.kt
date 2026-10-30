package dev.eltermino.core.importer

data class ParsedCa(val patterns: List<String>, val publicKey: String, val comment: String)

/** The `@cert-authority` lines of a `known_hosts` file; anything else is skipped. */
fun parseCertAuthorities(knownHosts: String): List<ParsedCa> = knownHosts.lineSequence().mapNotNull { line ->
    val fields = line.trim().split(Regex("\\s+"), limit = 5)
    if (fields.size < 4 || fields[0] != "@cert-authority") return@mapNotNull null
    ParsedCa(fields[1].split(','), "${fields[2]} ${fields[3]}", fields.getOrElse(4) { "" })
}.toList()
