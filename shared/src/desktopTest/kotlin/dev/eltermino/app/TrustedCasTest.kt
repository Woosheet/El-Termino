package dev.eltermino.app

import dev.eltermino.core.ssh.InvalidKeyException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TrustedCasTest {
    private val services = testServices()
    private val actions = WorkspaceActions(services)
    private val key = TestEnv.key("host_ca.pub").readText().trim()
    private val cas get() = services.workspace.workspace.value.trustedCas

    @Test
    fun addStoresKeyWithoutCommentAndFingerprint() = runTest {
        actions.saveTrustedCa(null, "Azienda", key, listOf("*.example.com"))
        val ca = cas.single()
        assertEquals(key.split(" ").take(2).joinToString(" "), ca.publicKey)
        assertTrue(ca.fingerprintSha256.startsWith("SHA256:"))
    }

    @Test
    fun editKeepsTheEntry() = runTest {
        actions.saveTrustedCa(null, "Azienda", key, listOf("*"))
        actions.saveTrustedCa(cas.single().id, "Nuovo nome", key, listOf("*.b"))
        assertEquals("Nuovo nome", cas.single().label)
    }

    @Test
    fun garbageKeyRefused() = runTest {
        assertFailsWith<InvalidKeyException> { actions.saveTrustedCa(null, "x", "non è una chiave", listOf("*")) }
        assertEquals(emptyList(), cas)
    }

    @Test
    fun skipsDuplicateCaOnImport() = runTest {
        val text = "@cert-authority [localhost]:2201 $key\n@cert-authority *.a $key\n"
        assertEquals(1, actions.importTrustedCas(text))
        assertEquals(0, actions.importTrustedCas(text))
        assertEquals(1, cas.size)
    }

    @Test
    fun unreadableLineDoesNotStopTheRest() = runTest {
        assertEquals(1, actions.importTrustedCas("@cert-authority * ssh-ed25519 ZZZ\n@cert-authority * $key\n"))
    }

    @Test
    fun removeDropsIt() = runTest {
        actions.saveTrustedCa(null, "Azienda", key, listOf("*"))
        actions.removeTrustedCa(cas.single().id)
        assertEquals(emptyList(), cas)
    }
}
