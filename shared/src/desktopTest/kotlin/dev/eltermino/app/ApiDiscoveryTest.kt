package dev.eltermino.app

import kotlin.test.Test
import kotlin.test.assertEquals

/** Reading what listens on a host, from what Linux, the BSDs and macOS print. */
class ApiDiscoveryTest {
    @Test
    fun readsLinuxProcTables() {
        val tcp = """
              sl  local_address rem_address   st tx_queue rx_queue tr tm->when retrnsmt   uid  timeout inode
               0: 0100007F:1F90 00000000:0000 0A 00000000:00000000 00:00000000 00000000     0        0 31337 1 0000000000000000 100 0 0 10 0
               1: 00000000:0016 00000000:0000 0A 00000000:00000000 00:00000000 00000000     0        0 12345 1 0000000000000000 100 0 0 10 0
               2: 0B00000A:0016 0400000A:D2F0 01 00000000:00000000 02:0009B1C3 00000000     0        0 44444 4 0000000000000000 20 4 30 10 -1
        """.trimIndent()
        val tcp6 = """
              sl  local_address                         remote_address                        st tx_queue rx_queue tr tm->when retrnsmt   uid  timeout inode
               0: 00000000000000000000000000000000:1F40 00000000000000000000000000000000:0000 0A 00000000:00000000 00:00000000 00000000     0        0 1 1
               1: 00000000000000000000000001000000:2382 00000000000000000000000000000000:0000 0A 00000000:00000000 00:00000000 00000000     0        0 2 1
               2: 0000000000000000FFFF00000B00000A:01BB 00000000000000000000000000000000:0000 0A 00000000:00000000 00:00000000 00000000     0        0 3 1
        """.trimIndent()
        assertEquals(
            listOf(
                ListeningPort("127.0.0.1", 8080),
                ListeningPort("0.0.0.0", 22),
                ListeningPort("::", 8000),
                ListeningPort("::1", 9090),
                ListeningPort("10.0.0.11", 443),
            ),
            parseListeningPorts(tcp + "\n" + tcp6),
            "the established connection on line 2 isn't listening",
        )
    }

    @Test
    fun readsNetstatOnLinuxAndMacOs() {
        val linux = """
            Active Internet connections (servers and established)
            Proto Recv-Q Send-Q Local Address           Foreign Address         State
            tcp        0      0 0.0.0.0:22              0.0.0.0:*               LISTEN
            tcp        0      0 127.0.0.1:5432          0.0.0.0:*               LISTEN
            tcp        0      0 10.0.0.11:22            10.0.0.4:53924          ESTABLISHED
            tcp6       0      0 :::8080                 :::*                    LISTEN
            udp        0      0 0.0.0.0:68              0.0.0.0:*
        """.trimIndent()
        assertEquals(
            listOf(ListeningPort("0.0.0.0", 22), ListeningPort("127.0.0.1", 5432), ListeningPort("::", 8080)),
            parseListeningPorts(linux),
        )
        val macOs = """
            tcp4       0      0  127.0.0.1.631          *.*                    LISTEN
            tcp46      0      0  *.3000                 *.*                    LISTEN
            tcp6       0      0  ::1.8080               *.*                    LISTEN
            tcp4       0      0  192.168.1.5.52344      17.57.146.20.443       ESTABLISHED
        """.trimIndent()
        assertEquals(
            listOf(ListeningPort("127.0.0.1", 631), ListeningPort("0.0.0.0", 3000), ListeningPort("::1", 8080)),
            parseListeningPorts(macOs),
        )
    }

    @Test
    fun aPortOpenToAllIsReachedOnTheLoopback() {
        assertEquals("127.0.0.1", ListeningPort("0.0.0.0", 80).probeHost())
        assertEquals("127.0.0.1", ListeningPort("::", 80).probeHost())
        assertEquals("127.0.0.1", ListeningPort("127.0.0.53", 53).probeHost())
        assertEquals("[::1]", ListeningPort("::1", 80).probeHost())
        assertEquals("10.0.0.11", ListeningPort("10.0.0.11", 443).probeHost())
        assertEquals("[fe80:0:0:0:0:0:0:1]", ListeningPort("fe80:0:0:0:0:0:0:1", 80).probeHost())
    }

    @Test
    fun namesTheProgramBehindEachPort() {
        val ss = """
            LISTEN 0      100        127.0.0.1:8110      0.0.0.0:*    users:(("java",pid=2211,fd=40))
            LISTEN 0      511          0.0.0.0:80        0.0.0.0:*
            LISTEN 0      511                *:3000            *:*    users:(("node",pid=3301,fd=22))
            --- ps
               2211 /usr/bin/java -Xmx512m -jar /opt/tde/tde-orders-2.4.1.jar --server.port=8110 --server.servlet.context-path=/orders
               3301 node /srv/web/dist/server.js
                 98 nginx: master process /usr/sbin/nginx -g daemon on; master_process on;
        """.trimIndent()
        val programs = parseListeningPrograms(ss)
        assertEquals(setOf(8110, 3000), programs.keys, "nginx belongs to root: no pid for it")
        assertEquals("tde-orders-2.4.1", programName(programs.getValue(8110)))
        assertEquals("/orders", contextPath(programs.getValue(8110)))
        assertEquals("node server.js", programName(programs.getValue(3000)))

        val netstat = """
            tcp        0      0 127.0.0.1:8080          0.0.0.0:*               LISTEN      23/busybox
            tcp        0      0 0.0.0.0:22              0.0.0.0:*               LISTEN      -
            --- ps
            PID   USER     COMMAND
               23 root     busybox httpd -p 127.0.0.1:8080 -h /srv/http
        """.trimIndent()
        assertEquals(mapOf(8080 to "root     busybox httpd -p 127.0.0.1:8080 -h /srv/http"), parseListeningPrograms(netstat))
        assertEquals("busybox httpd", programName("busybox httpd -p 127.0.0.1:8080 -h /srv/http"))
        assertEquals("nginx", programName("nginx: master process /usr/sbin/nginx -g daemon on;"))
        assertEquals("OrderApplication", programName("java -cp /opt/app/lib/* com.acme.orders.OrderApplication"))
        assertEquals(null, contextPath("java -jar app.jar --server.port=8080"))
        assertEquals("/api", contextPath("java -Dserver.servlet.context-path=/api/ -jar app.jar"))
    }

    @Test
    fun nothingListeningReadsAsNothing() {
        assertEquals(emptyList(), parseListeningPorts(""))
        assertEquals(emptyList(), parseListeningPorts("cat: /proc/net/tcp6: No such file or directory"))
    }
}
