package dev.eltermino.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DockerPanelTest {
    private val output = """
{"ID":"aaa111","Image":"busybox:1.36","Names":"job","Ports":"","State":"exited","Status":"Exited (0) 3 hours ago"}
{"ID":"bbb222","Image":"nginx:1.27","Names":"web,web-alias","Ports":"0.0.0.0:8080->80/tcp","State":"running","Status":"Up 2 hours"}
{"ID":"ccc333","Image":"postgres:16","Names":"db","Ports":"5432/tcp","State":"running","Status":"Up 2 hours","SomethingNew":"x"}
@
{"CPUPerc":"0.37%","ID":"bbb222","MemUsage":"23.4MiB / 7.65GiB","Name":"web"}
{"CPUPerc":"1.20%","ID":"ccc333","MemUsage":"80MiB / 7.65GiB","Name":"db"}
""".trimIndent()

    @Test
    fun runningContainersComeFirstWithTheirUsage() {
        val containers = parseContainers(output)
        assertEquals(listOf("db", "web", "job"), containers.map { it.name })
        val web = containers[1]
        assertEquals("nginx:1.27", web.image)
        assertEquals("0.0.0.0:8080->80/tcp", web.ports)
        assertEquals("0.37%", web.cpu)
        assertEquals("23.4MiB / 7.65GiB", web.memory)
        assertNull(containers.last().cpu, "a stopped container has no usage")
    }

    @Test
    fun noContainersIsAnEmptyList() {
        assertEquals(emptyList(), parseContainers("@\n"))
    }

    @Test
    fun errorsSayWhatToDo() {
        val denied = "permission denied while trying to connect to the Docker daemon socket at unix:///var/run/docker.sock: Get ..."
        assertEquals("L'utente non può usare Docker su web-01: va aggiunto al gruppo docker", dockerError("web-01", 1, denied))
        assertEquals("Docker non è installato su web-01", dockerError("web-01", 127, "sh: 1: docker: not found"))
        assertEquals("Docker non è avviato su web-01", dockerError("web-01", 1, "Cannot connect to the Docker daemon at unix:///var/run/docker.sock. Is the docker daemon running?"))
        assertEquals("Error response from daemon: No such container: x", dockerError("web-01", 1, "\nError response from daemon: No such container: x\n"))
    }

    @Test
    fun namesAreQuotedForTheShell() {
        val container = DockerContainer("id", "it's", "img", "running", "Up", "")
        assertEquals("docker logs -f --tail 200 'it'\\''s'", dockerLogsCommand(container))
    }
}
