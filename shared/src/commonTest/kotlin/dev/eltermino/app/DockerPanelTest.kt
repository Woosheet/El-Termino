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

    @Test
    fun listsImagesVolumesNetworks() {
        val images = parseResources(
            DockerKind.Image,
            """
            {"ID":"sha256:aaa","Repository":"nginx","Size":"192MB","Tag":"1.27"}
            {"ID":"sha256:bbb","Repository":"<none>","Size":"10MB","Tag":"<none>"}
            """.trimIndent(),
        )
        assertEquals(listOf("nginx:1.27", "sha256:bbb"), images.map { it.id })
        assertEquals("192MB", images[0].detail)
        assertEquals(listOf("data"), parseResources(DockerKind.Volume, """{"Driver":"local","Name":"data"}""").map { it.name })
        val networks = parseResources(
            DockerKind.Network,
            """
            {"Driver":"bridge","ID":"n1","Name":"bridge","Scope":"local"}
            {"Driver":"bridge","ID":"n2","Name":"app_net","Scope":"local"}
            """.trimIndent(),
        )
        assertEquals(mapOf("app_net" to true, "bridge" to false), networks.associate { it.name to it.removable }, "the default networks stay")
    }

    @Test
    fun composeLsParsed() {
        val projects = parseComposeProjects("""[{"Name":"shop","Status":"running(2)","ConfigFiles":"/srv/shop/a.yml,/srv/shop/b.yml"}]""")
        assertEquals(listOf(ComposeProject("shop", "running(2)", listOf("/srv/shop/a.yml", "/srv/shop/b.yml"))), projects)
        assertEquals(emptyList(), parseComposeProjects(""))
    }

    @Test
    fun quotesProjectAndIdInCommands() {
        assertEquals("docker volume rm 'a; rm -rf /'", dockerRemoveCommand(DockerKind.Volume, "a; rm -rf /"))
        assertEquals(
            "docker compose --project-name 'my app' -f '/srv/my app/c.yml' -f '/x'\\''y' restart",
            dockerComposeCommand(ComposeProject("my app", "", listOf("/srv/my app/c.yml", "/x'y")), ComposeAction.Restart),
        )
    }

    @Test
    fun runningContainerNotRemovable() {
        assertEquals(false, DockerContainer("1", "web", "i", "running", "", "").removable)
        assertEquals(true, DockerContainer("1", "web", "i", "exited", "", "").removable)
    }

    @Test
    fun imageInUseErrorShownInClear() {
        val stderr = "Error response from daemon: conflict: unable to remove repository reference \"nginx:1.27\" (must force) - container 3f2a is using its referenced image"
        assertEquals("È ancora in uso: ferma e rimuovi prima ciò che lo usa", dockerError("basic", 1, stderr))
    }
}
