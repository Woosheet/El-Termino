package dev.eltermino.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

class RecordingsTest {
    private class MemoryWriter : RecordingWriter {
        override val id = "test.cast"
        val lines = mutableListOf<String>()
        var closed = false
        override fun append(line: String) { lines += line }
        override fun close() { closed = true }
    }

    @Test
    fun recordsOutputAndResizesAsAsciicast() {
        val writer = MemoryWriter()
        val recorder = AsciicastRecorder(writer, "basic", 80, 24, 1_700_000_000)
        recorder.output("ls\r\n".encodeToByteArray())
        recorder.resized(100, 30)
        recorder.close()
        recorder.output("dopo".encodeToByteArray())
        assertEquals(true, writer.closed)
        val cast = parseAsciicast(writer.lines.joinToString("\n"))
        assertEquals("basic", cast.title)
        assertEquals(80 to 24, cast.width to cast.height)
        assertEquals(1_700_000_000, cast.startedEpochSeconds)
        assertEquals(listOf("o" to "ls\r\n", "r" to "100x30"), cast.events.map { it.kind to it.data })
    }

    @Test
    fun aCharacterSplitAcrossChunksIsWrittenWhole() {
        val writer = MemoryWriter()
        val recorder = AsciicastRecorder(writer, "t", 80, 24, 0)
        val bytes = "è€😀".encodeToByteArray()
        bytes.forEach { recorder.output(byteArrayOf(it)) }
        recorder.close()
        assertEquals("è€😀", parseAsciicast(writer.lines.joinToString("\n")).events.joinToString("") { it.data })
    }

    @Test
    fun completeLengthStopsBeforeAnUnfinishedCharacter() {
        val euro = "€".encodeToByteArray()
        assertEquals(1, completeUtf8Length(byteArrayOf('a'.code.toByte()) + euro.copyOf(2)))
        assertEquals(4, completeUtf8Length(byteArrayOf('a'.code.toByte()) + euro))
        assertEquals(0, completeUtf8Length(ByteArray(0)))
    }

    @Test
    fun summaryReadsTitleStartAndDuration() {
        val file = RecordingFile("x.cast", 120, 50, """{"version":2,"width":80,"height":24,"timestamp":42,"title":"db"}""", """[12.5, "o", "exit"]""")
        val summary = assertNotNull(file.summary())
        assertEquals(RecordingSummary("x.cast", "db", 42, 12.5, 120), summary)
        assertEquals(null, file.copy(firstLine = "spazzatura").summary())
    }

    @Test
    fun unreadableEventsAreSkippedAndAnotherFileIsRefused() {
        val cast = parseAsciicast("""{"version":2,"width":80,"height":24}""" + "\n[0.1, \"o\", \"a\"]\nrotto\n[0.2, \"o\", \"b\"]")
        assertEquals(listOf("a", "b"), cast.events.map { it.data })
        assertFailsWith<IllegalArgumentException> { parseAsciicast("non sono json") }
    }

    @Test
    fun pruneDeletesOnlyOldRecordings() {
        val deleted = mutableListOf<String>()
        val store = object : RecordingStore {
            override fun create(name: String) = error("unused")
            override fun list() = listOf(RecordingFile("old", 1, 0, "", ""), RecordingFile("new", 1, 9 * 86_400, "", ""))
            override fun read(id: String): ByteArray? = null
            override fun delete(id: String) { deleted += id }
        }
        store.prune(0, 10 * 86_400)
        assertEquals(emptyList(), deleted)
        store.prune(7, 10 * 86_400)
        assertEquals(listOf("old"), deleted)
    }
}
