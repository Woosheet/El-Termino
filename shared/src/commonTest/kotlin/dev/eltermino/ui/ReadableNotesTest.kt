package dev.eltermino.ui

import kotlin.test.Test
import kotlin.test.assertEquals

class ReadableNotesTest {
    @Test
    fun dropsTheMarkdownGitHubGenerates() {
        val notes = "## What's Changed\n* Barra del titolo **trasparente** by @Woosheet\n- Menu + sistemato\n\n**Full Changelog**: https://github.com/Woosheet/El-Termino/compare/v1.0.0...v1.0.1"
        assertEquals("What's Changed\n• Barra del titolo trasparente by @Woosheet\n• Menu + sistemato", readableNotes(notes))
    }

    @Test
    fun keepsAtMostTwelveLines() {
        assertEquals(12, readableNotes((1..20).joinToString("\n") { "* riga $it" }).lines().size)
    }
}
