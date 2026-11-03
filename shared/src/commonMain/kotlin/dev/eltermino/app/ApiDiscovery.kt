package dev.eltermino.app

/** A TCP port something listens on, with the address it's bound to as the host's tools print it. */
data class ListeningPort(val address: String, val port: Int)

/** The listening sockets on any Linux, with no need for ss or netstat; netstat elsewhere. */
const val LISTENING_PORTS_COMMAND = "cat /proc/net/tcp /proc/net/tcp6 2>/dev/null || netstat -an 2>/dev/null"

/** Ports whose protocol isn't HTTP: a GET there would only end up in their logs. */
val NOT_HTTP_PORTS = setOf(21, 22, 25, 53, 110, 111, 143, 389, 445, 465, 587, 993, 995, 1433, 1521, 2049, 2181, 3306, 3389, 5432, 5672, 6379, 9042, 9092, 11211, 27017)

/** Ports where HTTPS is the likelier answer, tried with it first. */
internal val TLS_PORTS = setOf(443, 8443, 9443)

/**
 * The listening TCP ports in [text]: Linux's /proc/net/tcp and tcp6 tables, or `netstat -an`
 * as Linux, the BSDs and macOS print it.
 */
fun parseListeningPorts(text: String): List<ListeningPort> {
    val found = mutableListOf<ListeningPort>()
    for (line in text.lineSequence()) {
        val fields = line.trim().split(Regex("\\s+"))
        if (fields.size < 4) continue
        if (fields[0].endsWith(":") && fields[0].dropLast(1).all(Char::isDigit)) {
            // "0: 0100007F:1F90 00000000:0000 0A ...": local address, remote, then the state; 0A is LISTEN.
            if (fields[3] != "0A") continue
            val local = fields[1].split(':')
            if (local.size != 2) continue
            val address = procAddress(local[0]) ?: continue
            found += ListeningPort(address, local[1].toIntOrNull(16) ?: continue)
        } else if (fields[0].startsWith("tcp") && "LISTEN" in fields) {
            // "tcp 0 0 0.0.0.0:22 0.0.0.0:* LISTEN" on Linux, "tcp4 0 0 *.22 *.* LISTEN" on macOS.
            val local = fields[3]
            val cut = maxOf(local.lastIndexOf(':'), local.lastIndexOf('.'))
            if (cut < 0) continue
            val port = local.substring(cut + 1).toIntOrNull() ?: continue
            val address = when (val bound = local.substring(0, cut)) {
                "*", "" -> "0.0.0.0"
                ":", "::" -> "::"
                else -> bound
            }
            found += ListeningPort(address, port)
        }
    }
    return found.distinct()
}

/** An address from /proc/net: IPv4 as one word, IPv6 as four, each in the host's little-endian order. */
internal fun procAddress(hex: String): String? {
    val words = when (hex.length) {
        8, 32 -> hex.chunked(8)
        else -> return null
    }
    val bytes = words.flatMap { word -> word.chunked(2).reversed() }.map { it.toIntOrNull(16) ?: return null }
    if (bytes.size == 4) return bytes.joinToString(".")
    return when {
        bytes.all { it == 0 } -> "::"
        bytes.dropLast(1).all { it == 0 } && bytes.last() == 1 -> "::1"
        bytes.take(10).all { it == 0 } && bytes[10] == 0xFF && bytes[11] == 0xFF -> bytes.drop(12).joinToString(".")
        else -> bytes.chunked(2).joinToString(":") { ((it[0] shl 8) or it[1]).toString(16) }
    }
}

/** Where to reach this port through the tunnel: the loopback when it's open to all, its own address otherwise. */
fun ListeningPort.probeHost(): String = when {
    address == "0.0.0.0" || address == "::" || address.startsWith("127.") -> "127.0.0.1"
    address == "::1" -> "[::1]"
    ':' in address -> "[$address]"
    else -> address
}

/**
 * The process on each listening port, then every command line: ss or netstat with -p, which show
 * the processes of this user only unless it's root, then ps.
 */
const val PROCESSES_COMMAND =
    "ss -ltnpH 2>/dev/null || netstat -ltnp 2>/dev/null || busybox netstat -ltnp 2>/dev/null; echo '--- ps'; ps -eo pid=,args= 2>/dev/null || busybox ps -o pid,args 2>/dev/null"

/** The command line behind each listening port, from what [PROCESSES_COMMAND] prints. */
fun parseListeningPrograms(text: String): Map<Int, String> {
    val sockets = text.substringBefore("--- ps")
    val processes = text.substringAfter("--- ps", "")
    val argsByPid = processes.lineSequence().mapNotNull { line ->
        val trimmed = line.trim()
        val pid = trimmed.substringBefore(' ').toIntOrNull() ?: return@mapNotNull null
        pid to trimmed.substringAfter(' ').trim()
    }.toMap()
    val programs = mutableMapOf<Int, String>()
    for (line in sockets.lineSequence()) {
        // ss: "LISTEN 0 4096 127.0.0.1:8080 0.0.0.0:* users:(("java",pid=1234,fd=56))"; netstat: "... LISTEN 1234/java".
        val fields = line.trim().split(Regex("\\s+"))
        if (fields.size < 4) continue
        val port = fields[3].substringAfterLast(':').toIntOrNull() ?: continue
        val pid = (Regex("""pid=(\d+)""").find(line) ?: Regex("""\s(\d+)/\S+\s*$""").find(line))?.groupValues?.get(1)?.toIntOrNull() ?: continue
        argsByPid[pid]?.let { programs[port] = it }
    }
    return programs
}

private val SCRIPT_RUNNERS = setOf("node", "python", "python3", "ruby", "php", "deno", "bun", "busybox", "dotnet", "uvicorn", "gunicorn")

/** A short name for a command line: the jar or main class it runs, or the program and its script. */
fun programName(args: String): String {
    val tokens = args.split(Regex("\\s+")).filter { it.isNotEmpty() }
    tokens.indexOf("-jar").takeIf { it >= 0 }?.let { tokens.getOrNull(it + 1) }?.let { jar ->
        return jar.substringAfterLast('/').removeSuffix(".jar")
    }
    val program = tokens.firstOrNull()?.substringAfterLast('/')?.trimEnd(':') ?: return args
    val rest = tokens.drop(1).filterNot { it.startsWith("-") }
    if (program == "java") rest.lastOrNull { '.' in it && '/' !in it }?.let { return it.substringAfterLast('.') }
    val script = rest.firstOrNull()?.substringAfterLast('/')
    return if (program in SCRIPT_RUNNERS && script != null) "$program $script" else program
}

/** The path a Spring application serves under, when its command line sets one. */
fun contextPath(args: String): String? =
    Regex("""(?:server\.servlet\.context-path|server\.context-path|spring\.webflux\.base-path)=(\S+)""").find(args)
        ?.groupValues?.get(1)?.trim('"', '\'')?.trimEnd('/')?.takeIf { it.startsWith("/") && it.length > 1 }
