package dev.eltermino.core.importer

/**
 * Reads the CSV that Termius imports and that exporters for it write. Termius doesn't
 * publish the template; the header in the wild is
 * `Groups,Label,Tags,Hostname/IP,Protocol,Port,Username,Password`, sometimes followed by
 * key and proxy columns. Columns are matched by name, in any order and case, and every
 * column except the address is optional.
 */
object TermiusCsvParser {
    private enum class Column(vararg val names: String) {
        Groups("groups", "group"),
        Subgroup("subgroup", "subgroups"),
        Label("label", "name", "alias"),
        Tags("tags", "tag"),
        Address("hostnameip", "hostname", "host", "ip", "ipaddress", "address"),
        Protocol("protocol"),
        Port("port"),
        Username("username", "user", "login"),
        Password("password"),
        IdentityFile("identityfile", "sshkey", "key", "privatekey"),
        KeyPassphrase("keypassphrase", "passphrase"),
        ProxyType("proxytype"),
        ProxyHost("proxyhost"),
        ;

        companion object {
            fun of(header: String): Column? {
                val normalized = header.lowercase().filter { it.isLetterOrDigit() }
                return entries.firstOrNull { normalized in it.names }
            }
        }
    }

    fun parse(text: String): ImportResult {
        val records = Csv.read(text, Csv.guessDelimiter(text)).filterNot { r -> r.fields.all { it.isBlank() } }
        if (records.isEmpty()) return ImportResult(emptyList(), emptyList())

        val warnings = mutableListOf<ImportWarning>()
        val header = records.first()
        val columns = header.fields.map { Column.of(it.trim()) }
        if (Column.Address !in columns) {
            warnings += ImportWarning(header.line, "Manca la colonna Hostname/IP: il file non sembra un CSV di Termius")
            return ImportResult(emptyList(), warnings)
        }

        val hosts = mutableListOf<ImportedHost>()
        val seen = mutableSetOf<List<Any?>>()
        var ignoredPassphrases = false

        for (record in records.drop(1)) {
            if (record.fields.size > header.fields.size) {
                warnings += ImportWarning(
                    record.line,
                    "La riga ha più colonne dell'intestazione: controlla le virgole non racchiuse tra virgolette",
                )
            }
            val row = columns.zip(record.fields).mapNotNull { (column, value) -> column?.let { it to value.trim() } }
                .groupBy({ it.first }, { it.second })
                .mapValues { (_, values) -> values.firstOrNull { it.isNotEmpty() } ?: "" }
            fun get(column: Column) = row[column].orEmpty()

            val protocol = get(Column.Protocol).lowercase()
            when {
                protocol.isEmpty() || protocol == "ssh" -> Unit
                protocol == "mosh" -> warnings += ImportWarning(record.line, "Host Mosh importato come SSH")
                else -> {
                    warnings += ImportWarning(record.line, "Protocollo ${get(Column.Protocol)} non supportato: riga saltata")
                    continue
                }
            }

            var address = get(Column.Address)
            var username = get(Column.Username).ifEmpty { null }
            var portText = get(Column.Port)
            if (address.isEmpty()) {
                warnings += ImportWarning(record.line, "Manca l'indirizzo dell'host: riga saltata")
                continue
            }
            // Some sheets put user@host or host:port in the address column.
            if ('@' in address && username == null) {
                username = address.substringBeforeLast('@').ifEmpty { null }
                address = address.substringAfterLast('@')
            }
            if (address.startsWith("[") && "]" in address) {
                val after = address.substringAfter(']')
                if (after.startsWith(":") && portText.isEmpty()) portText = after.substring(1)
                address = address.substring(1, address.indexOf(']'))
            } else if (address.count { it == ':' } == 1 && portText.isEmpty()) {
                portText = address.substringAfter(':')
                address = address.substringBefore(':')
            }
            if (!isValidAddress(address)) {
                warnings += ImportWarning(record.line, "Indirizzo non valido: $address. Riga saltata")
                continue
            }

            val port = if (portText.isEmpty()) {
                22
            } else {
                portText.toIntOrNull()?.takeIf { it in 1..65535 } ?: run {
                    warnings += ImportWarning(record.line, "Porta non valida: $portText. Riga saltata")
                    null
                }
            } ?: continue

            if (get(Column.KeyPassphrase).isNotEmpty()) ignoredPassphrases = true
            if (get(Column.ProxyType).isNotEmpty() || get(Column.ProxyHost).isNotEmpty()) {
                warnings += ImportWarning(record.line, "Proxy HTTP/SOCKS non supportato: host importato senza proxy")
            }

            val groupPath = (splitGroups(get(Column.Groups)) + splitGroups(get(Column.Subgroup)))
            val host = ImportedHost(
                label = get(Column.Label).ifEmpty { address },
                address = address,
                port = port,
                username = username,
                password = get(Column.Password).ifEmpty { null },
                groupPath = groupPath,
                tags = get(Column.Tags).split(',', ';').map { it.trim() }.filter { it.isNotEmpty() }.distinct(),
                identityFiles = listOfNotNull(get(Column.IdentityFile).ifEmpty { null }),
            )
            val identity = listOf(host.label, host.address, host.port, host.username, host.groupPath)
            if (!seen.add(identity)) {
                warnings += ImportWarning(record.line, "Host ${host.label} duplicato: riga saltata")
                continue
            }
            hosts += host
        }

        if (ignoredPassphrases) {
            warnings += ImportWarning(null, "Le passphrase delle chiavi non vengono importate: le inserirai quando aggiungi la chiave")
        }
        return ImportResult(hosts, warnings)
    }

    private fun splitGroups(value: String): List<String> =
        value.split('/', '\\').map { it.trim() }.filter { it.isNotEmpty() }

    /** Hostname, IPv4 or IPv6 (brackets already removed). Lenient: rejects only what can't work. */
    private fun isValidAddress(address: String): Boolean {
        if (address.isEmpty() || address.length > 253) return false
        return if (':' in address) {
            address.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' || it == ':' || it == '.' || it == '%' }
        } else {
            address.all { it.isLetterOrDigit() || it == '.' || it == '-' || it == '_' } &&
                !address.startsWith('-') && !address.startsWith('.')
        }
    }
}
