package dev.eltermino.app

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/** A Resources tab: what a host is using, read every few seconds while the tab is open. */
class MonitorTab internal constructor(id: String, override val hostId: String, label: String) : Tab(id, label) {
    internal val mutableLease = MutableStateFlow<LinkLease?>(null)
    internal var job: Job? = null
    val lease: StateFlow<LinkLease?> = mutableLease.asStateFlow()

    internal val mutableSamples = MutableStateFlow<List<ResourceSample>>(emptyList())

    /** Oldest first, the last [HISTORY]: enough for a few minutes of graph. */
    val samples: StateFlow<List<ResourceSample>> = mutableSamples.asStateFlow()

    internal val mutableError = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = mutableError.asStateFlow()

    internal companion object {
        const val HISTORY = 60
        val INTERVAL = 3.seconds
    }
}

data class DiskUsage(val mount: String, val usedKb: Long, val sizeKb: Long)

data class ResourceSample(
    val uptimeSeconds: Long,
    /** 1, 5 and 15 minutes. */
    val load: List<Double>,
    val cpus: Int,
    internal val cpuBusy: Long,
    internal val cpuTotal: Long,
    /** Since the previous sample; null for the first one. */
    val cpuPercent: Double?,
    val memTotalKb: Long,
    val memAvailableKb: Long,
    val swapTotalKb: Long,
    val swapFreeKb: Long,
    internal val netRxBytes: Long,
    internal val netTxBytes: Long,
    val rxBytesPerSecond: Double?,
    val txBytesPerSecond: Double?,
    val disks: List<DiskUsage>,
) {
    val memUsedKb: Long get() = memTotalKb - memAvailableKb
    val swapUsedKb: Long get() = swapTotalKb - swapFreeKb
}

class MonitorException(message: String) : Exception(message)

/** One exec for everything, sections split by a line with "@". Plain POSIX, nothing to install. */
internal const val MONITOR_COMMAND =
    "cat /proc/uptime /proc/loadavg; echo @; cat /proc/meminfo; echo @; head -1 /proc/stat; grep -c '^cpu[0-9]' /proc/stat; " +
        "echo @; cat /proc/net/dev; echo @; df -kP"

/**
 * Reads [MONITOR_COMMAND]'s output. CPU and network are rates, so they need [previous]
 * and the [elapsedSeconds] since it.
 */
internal fun parseSample(text: String, previous: ResourceSample?, elapsedSeconds: Double): ResourceSample {
    val sections = text.split(Regex("""(?m)^@\s*$""")).map { it.trim().lines() }
    if (sections.size < 5) throw MonitorException("Il monitor legge /proc: funziona sugli host Linux")
    val (times, memory, stat, net, df) = sections
    fun bad(what: String): Nothing = throw MonitorException("Non riesco a leggere $what da questo host")

    val uptime = times.getOrNull(0)?.substringBefore(' ')?.toDoubleOrNull() ?: bad("/proc/uptime")
    val load = times.getOrNull(1)?.split(' ')?.take(3)?.map { it.toDoubleOrNull() ?: bad("/proc/loadavg") } ?: bad("/proc/loadavg")

    val mem = memory.mapNotNull { line ->
        val name = line.substringBefore(':', "")
        line.substringAfter(':').trim().substringBefore(' ').toLongOrNull()?.let { name to it }
    }.toMap()
    val memTotal = mem["MemTotal"] ?: bad("/proc/meminfo")
    // Older kernels have no MemAvailable; free plus caches is what it approximates.
    val memAvailable = mem["MemAvailable"] ?: ((mem["MemFree"] ?: 0) + (mem["Buffers"] ?: 0) + (mem["Cached"] ?: 0))

    val cpuFields = stat.getOrNull(0)?.takeIf { it.startsWith("cpu ") }?.split(Regex("\\s+"))?.drop(1)?.mapNotNull { it.toLongOrNull() } ?: bad("/proc/stat")
    val cpuTotal = cpuFields.sum()
    // idle and iowait are the time the CPUs had nothing to run.
    val cpuBusy = cpuTotal - cpuFields.getOrElse(3) { 0 } - cpuFields.getOrElse(4) { 0 }
    val cpus = stat.getOrNull(1)?.trim()?.toIntOrNull() ?: 1

    var rx = 0L
    var tx = 0L
    net.filter { ':' in it }.forEach { line ->
        val name = line.substringBefore(':').trim()
        val fields = line.substringAfter(':').trim().split(Regex("\\s+")).mapNotNull { it.toLongOrNull() }
        if (name != "lo" && fields.size >= 9) {
            rx += fields[0]
            tx += fields[8]
        }
    }

    val disks = mutableListOf<DiskUsage>()
    df.drop(1).forEach { line ->
        val fields = line.trim().split(Regex("\\s+"))
        if (fields.size < 6) return@forEach
        val size = fields[1].toLongOrNull() ?: return@forEach
        val used = fields[2].toLongOrNull() ?: return@forEach
        val mount = fields.drop(5).joinToString(" ")
        // The root and real devices; the same device mounted twice shows once.
        val real = mount == "/" || fields[0].startsWith("/dev/")
        if (real && size > 0 && disks.none { it.sizeKb == size && it.usedKb == used }) disks += DiskUsage(mount, used, size)
    }

    val rate = previous != null && elapsedSeconds > 0
    val cpuDelta = previous?.let { cpuTotal - it.cpuTotal } ?: 0
    return ResourceSample(
        uptimeSeconds = uptime.toLong(),
        load = load,
        cpus = cpus,
        cpuBusy = cpuBusy,
        cpuTotal = cpuTotal,
        cpuPercent = if (previous != null && cpuDelta > 0) (cpuBusy - previous.cpuBusy) * 100.0 / cpuDelta else null,
        memTotalKb = memTotal,
        memAvailableKb = memAvailable,
        swapTotalKb = mem["SwapTotal"] ?: 0,
        swapFreeKb = mem["SwapFree"] ?: 0,
        netRxBytes = rx,
        netTxBytes = tx,
        // A counter that went down was reset (interface restarted): no rate this time.
        rxBytesPerSecond = if (rate && rx >= previous!!.netRxBytes) (rx - previous.netRxBytes) / elapsedSeconds else null,
        txBytesPerSecond = if (rate && tx >= previous!!.netTxBytes) (tx - previous.netTxBytes) / elapsedSeconds else null,
        disks = disks,
    )
}

/** Reads [tab]'s host every few seconds over [lease]'s link, until the tab closes. */
internal suspend fun monitor(tab: MonitorTab, lease: LinkLease) {
    lease.link.state.collectLatest { state ->
        when (state) {
            is LinkState.Up -> {
                // Rates restart with each connection, so a reconnect doesn't show a made-up spike.
                var previous: ResourceSample? = null
                var last = TimeSource.Monotonic.markNow()
                while (true) {
                    try {
                        val result = state.connection.exec(MONITOR_COMMAND)
                        val now = TimeSource.Monotonic.markNow()
                        val sample = parseSample(result.stdout.decodeToString(), previous, (now - last).inWholeMilliseconds / 1000.0)
                        previous = sample
                        last = now
                        tab.mutableSamples.value = (tab.samples.value + sample).takeLast(MonitorTab.HISTORY)
                        tab.mutableError.value = null
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        tab.mutableError.value = if (e is MonitorException) e.message else describe(e)
                    }
                    delay(MonitorTab.INTERVAL)
                }
            }
            is LinkState.Down -> tab.mutableError.value = state.message
            is LinkState.Retrying -> tab.mutableError.value = "Riconnessione: ${state.message}"
            LinkState.Connecting -> Unit
        }
    }
}
