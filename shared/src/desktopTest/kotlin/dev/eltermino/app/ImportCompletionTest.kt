package dev.eltermino.app

import dev.eltermino.core.importer.ImportedHost
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/** Importing again after an import that missed the credentials fills them in, without duplicates. */
class ImportCompletionTest {
    @Test
    fun aSecondImportCompletesHostsInsteadOfDuplicatingThem(): Unit = runBlocking {
        val services = testServices()
        val actions = WorkspaceActions(services)
        val importer = ImportService(services, actions)
        val bare = listOf(ImportedHost("web-01", "10.0.0.5"), ImportedHost("db-01", "10.0.0.6", port = 2222))
        assertEquals(2, importer.apply(bare).hostsAdded)

        val withCredentials = listOf(
            ImportedHost("web-01", "10.0.0.5", username = "deploy", password = "segreta"),
            ImportedHost("db-01", "10.0.0.6", port = 2222, username = "postgres"),
            ImportedHost("nuovo", "10.0.0.7", username = "root"),
        )
        val summary = importer.apply(withCredentials)
        assertEquals(1, summary.hostsAdded)
        assertEquals(2, summary.hostsCompleted)

        val workspace = services.workspace.workspace.value
        assertEquals(3, workspace.hosts.size, "no duplicates")
        val web = workspace.hosts.single { it.label == "web-01" }
        val identity = assertNotNull(workspace.identity(web.identityId))
        assertEquals("deploy", identity.username)
        assertNotNull(identity.passwordSecretId)
        assertEquals("postgres", workspace.usernameFor(workspace.hosts.single { it.label == "db-01" }))

        assertEquals(0, importer.apply(withCredentials).hostsAdded, "a third time changes nothing")
        assertEquals(3, services.workspace.workspace.value.hosts.size)
    }
}
