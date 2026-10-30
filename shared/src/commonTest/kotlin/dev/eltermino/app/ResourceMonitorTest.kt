package dev.eltermino.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class ResourceMonitorTest {
    // Trimmed from a testenv host (Debian in Docker).
    private fun output(cpuUser: Long, cpuIdle: Long, rx: Long, tx: Long) = """
632781.26 11299388.14
2.67 2.53 2.45 5/710 1612
@
MemTotal:        8022224 kB
MemFree:         2696656 kB
MemAvailable:    5747292 kB
SwapTotal:       1048572 kB
SwapFree:         826472 kB
@
cpu  $cpuUser 0 1000 $cpuIdle 500 0 0 0 0 0
18
@
Inter-|   Receive                                                |  Transmit
 face |bytes    packets errs drop fifo frame compressed multicast|bytes    packets errs drop fifo colls carrier compressed
    lo:   42139     494    0    0    0     0          0         0    42139     494    0    0    0     0       0          0
  eth0: $rx    7918    0    0    0     0          0         0 $tx    9765    0    0    0     0       0          0
@
Filesystem           1024-blocks      Used Available Capacity Mounted on
overlay                954925484  12010464 894333748       2% /
tmpfs                      65536         0     65536       0% /dev
/run/host_mark/Users   971298980 431298980 540000000      45% /testenv/keys
/dev/vda1              954925484  12010464 894333748       2% /etc/hosts
/dev/sdb1                1000000    950000     50000      95% /mnt/backup disk
""".trimIndent()

    private fun withProcesses(section: String) = output(1000, 10_000, 5_000, 100_000) + "\n@\n" + section

    @Test
    fun parsesTopProcesses() {
        val sample = parseSample(
            withProcesses(
                """
                    PID USER     %CPU %MEM COMMAND
                   1234 postgres 12.5  3.1 postgres: writer
                      1 root      0.0  0.1 /sbin/init
                """.trimIndent(),
            ),
            null,
            0.0,
        )
        assertEquals(
            listOf(
                ProcessInfo(1234, "postgres", 12.5, 3.1, "postgres: writer"),
                ProcessInfo(1, "root", 0.0, 0.1, "/sbin/init"),
            ),
            sample.processes,
        )
    }

    @Test
    fun commandWithSpacesKept() {
        val sample = parseSample(withProcesses("7 www 1.0 2.0 nginx: worker process   -g daemon off;"), null, 0.0)
        assertEquals("nginx: worker process   -g daemon off;", sample.processes.single().command)
    }

    @Test
    fun missingPsLeavesOtherMetrics() {
        val empty = parseSample(withProcesses(""), null, 0.0)
        assertEquals(emptyList(), empty.processes)
        assertEquals(632781, empty.uptimeSeconds)
        // An older command's output, with no such section at all.
        assertEquals(emptyList(), parseSample(output(1000, 10_000, 5_000, 100_000), null, 0.0).processes)
    }

    @Test
    fun busyboxPsTolerated() {
        // BusyBox ignores some columns and prints what it knows.
        val sample = parseSample(withProcesses("PID   USER     COMMAND\n    1 root     init\n   12 root     [kworker]"), null, 0.0)
        assertEquals(emptyList(), sample.processes)
        assertEquals(632781, sample.uptimeSeconds)
    }

    @Test
    fun readsEverythingAndTheRatesFromTheSecondSample() {
        val first = parseSample(output(cpuUser = 1000, cpuIdle = 10_000, rx = 5_000, tx = 100_000), null, 0.0)
        assertEquals(632781, first.uptimeSeconds)
        assertEquals(listOf(2.67, 2.53, 2.45), first.load)
        assertEquals(18, first.cpus)
        assertEquals(8022224 - 5747292L, first.memUsedKb)
        assertEquals(1048572 - 826472L, first.swapUsedKb)
        assertNull(first.cpuPercent)
        assertNull(first.rxBytesPerSecond)
        // The root and the real disks; the root's device mounted again on /etc/hosts shows once.
        assertEquals(listOf("/", "/mnt/backup disk"), first.disks.map { it.mount })

        // 300 busy jiffies out of 1000 in all, and 6000 bytes in over 2 seconds.
        val second = parseSample(output(cpuUser = 1300, cpuIdle = 10_700, rx = 11_000, tx = 100_400), first, 2.0)
        assertEquals(30.0, second.cpuPercent)
        assertEquals(3000.0, second.rxBytesPerSecond)
        assertEquals(200.0, second.txBytesPerSecond)

        // A counter that went down (interface restarted) gives no rate rather than a negative one.
        assertNull(parseSample(output(1300, 10_700, rx = 10, tx = 10), second, 2.0).rxBytesPerSecond)
    }

    @Test
    fun aHostWithoutProcSaysItNeedsLinux() {
        val error = assertFailsWith<MonitorException> { parseSample("cat: /proc/uptime: No such file or directory\n", null, 0.0) }
        assertEquals("Il monitor legge /proc: funziona sugli host Linux", error.message)
    }
}
