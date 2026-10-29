package dev.eltermino.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReleasesTest {
    /** Trimmed from what api.github.com/repos/…/releases/latest answers. */
    private val latest = """
        {
          "html_url": "https://github.com/Woosheet/El-Termino/releases/tag/v1.0.1",
          "tag_name": "v1.0.1",
          "name": "El Termino 1.0.1",
          "draft": false,
          "prerelease": false,
          "body": "## What's Changed\n* Barra del titolo trasparente",
          "assets": [
            {
              "name": "El-Termino-1.0.1-macos-arm64.dmg",
              "size": 129695638,
              "browser_download_url": "https://github.com/Woosheet/El-Termino/releases/download/v1.0.1/El-Termino-1.0.1-macos-arm64.dmg"
            },
            {
              "name": "El-Termino-1.0.1-windows-x64.msi",
              "size": 90000000,
              "browser_download_url": "https://github.com/Woosheet/El-Termino/releases/download/v1.0.1/El-Termino-1.0.1-windows-x64.msi"
            }
          ]
        }
    """.trimIndent()

    @Test
    fun readsTheVersionNotesAndThisDevicesFile() {
        val release = Releases.parse(latest, "-macos-arm64.dmg")
        assertEquals("1.0.1", release.version)
        assertEquals("## What's Changed\n* Barra del titolo trasparente", release.notes)
        assertEquals("https://github.com/Woosheet/El-Termino/releases/tag/v1.0.1", release.pageUrl)
        assertEquals("El-Termino-1.0.1-macos-arm64.dmg", release.asset?.name)
        assertEquals(129_695_638L, release.asset?.sizeBytes)
        assertTrue(release.asset!!.url.endsWith("/v1.0.1/El-Termino-1.0.1-macos-arm64.dmg"))
    }

    @Test
    fun noFileWhenNoneIsForThisDevice() {
        assertNull(Releases.parse(latest, "-macos-x64.dmg").asset)
        assertNull(Releases.parse(latest, null).asset)
    }

    @Test
    fun toleratesMissingNotesAndAssets() {
        val release = Releases.parse("""{"tag_name":"v2.0.0","body":null}""", "-macos-arm64.dmg")
        assertEquals("2.0.0", release.version)
        assertEquals("", release.notes)
        assertNull(release.asset)
    }

    @Test
    fun refusesAnAnswerWithoutATag() {
        assertFailsWith<IllegalArgumentException> { Releases.parse("""{"message":"Not Found"}""", null) }
    }

    @Test
    fun comparesVersionsNumberByNumber() {
        assertTrue(Releases.isNewer("1.0.1", "1.0.0"))
        assertTrue(Releases.isNewer("1.10.0", "1.9.3"))
        assertTrue(Releases.isNewer("v2.0.0", "1.99.99"))
        assertTrue(Releases.isNewer("1.0.0.1", "1.0.0"))
        assertFalse(Releases.isNewer("1.0.0", "1.0.0"))
        assertFalse(Releases.isNewer("1.0", "1.0.0"))
        assertFalse(Releases.isNewer("1.0.0", "1.0.1"))
        assertFalse(Releases.isNewer("1.2.0-beta", "1.2.0"))
    }
}
