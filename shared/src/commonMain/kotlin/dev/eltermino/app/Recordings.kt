package dev.eltermino.app

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlin.time.TimeSource

/** Terminal recordings kept on this device, one asciicast v2 file each. */
interface RecordingStore {
    /** A new, empty recording; append its lines, then close it. */
    fun create(name: String): RecordingWriter
    fun list(): List<RecordingFile>
    fun read(id: String): ByteArray?
    fun delete(id: String)
}

interface RecordingWriter {
    val id: String
    fun append(line: String)
    fun close()
}

/** [firstLine] is the header, [lastLine] the last event: enough to list without reading it all. */
data class RecordingFile(val id: String, val sizeBytes: Long, val modifiedEpochSeconds: Long, val firstLine: String, val lastLine: String)

/** A recording's header and its last moment, for the list. */
data class RecordingSummary(val id: String, val title: String, val startedEpochSeconds: Long, val durationSeconds: Double, val sizeBytes: Long)

data class AsciicastEvent(val time: Double, val kind: String, val data: String)

data class Asciicast(val title: String, val width: Int, val height: Int, val startedEpochSeconds: Long, val events: List<AsciicastEvent>) {
    val durationSeconds: Double get() = events.lastOrNull()?.time ?: 0.0
}

/**
 * Writes what a terminal shows as asciicast v2: a header line, then `[seconds, "o", text]`
 * for output and `[seconds, "r", "COLSxROWS"]` for resizes. Only output is kept, not what
 * was typed, so a password typed at a prompt isn't in the file.
 */
class AsciicastRecorder(
    private val writer: RecordingWriter,
    title: String,
    cols: Int,
    rows: Int,
    startedEpochSeconds: Long,
) {
    private val start = TimeSource.Monotonic.markNow()
    private var pending = ByteArray(0)
    private var closed = false

    init {
        writer.append(
            buildJsonObject {
                put("version", 2)
                put("width", cols)
                put("height", rows)
                put("timestamp", startedEpochSeconds)
                put("title", title)
                putJsonObject("env") { put("TERM", "xterm-256color") }
            }.toString(),
        )
    }

    fun output(bytes: ByteArray) = synchronized(this) {
        if (closed) return
        val all = pending + bytes
        // A UTF-8 character can arrive split across two chunks: keep its start for the next one.
        val complete = completeUtf8Length(all)
        pending = all.copyOfRange(complete, all.size)
        if (complete > 0) event("o", all.decodeToString(0, complete))
    }

    fun resized(cols: Int, rows: Int) = synchronized(this) {
        if (!closed) event("r", "${cols}x$rows")
    }

    fun close() = synchronized(this) {
        if (closed) return
        closed = true
        if (pending.isNotEmpty()) event("o", pending.decodeToString())
        writer.close()
    }

    private fun event(kind: String, data: String) {
        val ms = start.elapsedNow().inWholeMilliseconds
        writer.append("[${ms / 1000}.${(ms % 1000).toString().padStart(3, '0')}, \"$kind\", ${JsonPrimitive(data)}]")
    }
}

/** How many leading bytes of [bytes] end on a whole UTF-8 character. */
internal fun completeUtf8Length(bytes: ByteArray): Int {
    // Walk back over at most 3 continuation bytes to the start of the last character.
    var i = bytes.size - 1
    var back = 0
    while (i >= 0 && back < 3 && bytes[i].toInt() and 0xC0 == 0x80) {
        i--
        back++
    }
    if (i < 0) return bytes.size
    val lead = bytes[i].toInt() and 0xFF
    val needed = when {
        lead < 0x80 -> 1
        lead shr 5 == 0b110 -> 2
        lead shr 4 == 0b1110 -> 3
        lead shr 3 == 0b11110 -> 4
        else -> 1
    }
    return if (back + 1 < needed) i else bytes.size
}

private val json = Json { ignoreUnknownKeys = true }

/** Reads an asciicast v2 file; lines it can't read are skipped. */
fun parseAsciicast(text: String): Asciicast {
    val lines = text.lineSequence().filter { it.isNotBlank() }.toList()
    val header = runCatching { json.parseToJsonElement(lines.first()).jsonObject }.getOrNull()
        ?: throw IllegalArgumentException("Non è una registrazione asciicast")
    val events = lines.drop(1).mapNotNull(::parseEvent)
    return Asciicast(
        title = header["title"]?.jsonPrimitive?.content.orEmpty(),
        width = header["width"]?.jsonPrimitive?.int ?: 80,
        height = header["height"]?.jsonPrimitive?.int ?: 24,
        startedEpochSeconds = header["timestamp"]?.jsonPrimitive?.long ?: 0,
        events = events,
    )
}

private fun parseEvent(line: String): AsciicastEvent? = runCatching {
    val array = json.parseToJsonElement(line) as JsonArray
    AsciicastEvent(array[0].jsonPrimitive.double, array[1].jsonPrimitive.content, array[2].jsonPrimitive.content)
}.getOrNull()

internal fun RecordingFile.summary(): RecordingSummary? {
    val header = runCatching { json.parseToJsonElement(firstLine).jsonObject }.getOrNull() ?: return null
    return RecordingSummary(
        id = id,
        title = header["title"]?.jsonPrimitive?.content.orEmpty(),
        startedEpochSeconds = header["timestamp"]?.jsonPrimitive?.long ?: modifiedEpochSeconds,
        durationSeconds = parseEvent(lastLine)?.time ?: 0.0,
        sizeBytes = sizeBytes,
    )
}

/** Deletes recordings last written more than [days] ago; 0 keeps them all. */
fun RecordingStore.prune(days: Int, nowEpochSeconds: Long) {
    if (days <= 0) return
    list().filter { it.modifiedEpochSeconds < nowEpochSeconds - days * 86_400L }.forEach { delete(it.id) }
}
