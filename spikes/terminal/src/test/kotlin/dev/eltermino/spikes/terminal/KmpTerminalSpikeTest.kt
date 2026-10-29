package dev.eltermino.spikes.terminal

import com.johnan.terminal.core.TerminalEmulator
import com.johnan.terminal.core.TerminalScreenState
import com.johnan.terminal.core.terminalConfig
import dev.eltermino.spikes.spikeResult
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val LIB = "kmp-terminal"

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class KmpTerminalSpikeTest {
    private val out = File("build/snapshots").apply { mkdirs() }
    private lateinit var ssh: SpikeSsh
    private val captured = mutableMapOf<String, TerminalScreenState>()

    @BeforeAll
    fun connect() {
        ssh = SpikeSsh()
    }

    @AfterAll
    fun disconnect() {
        ssh.close()
    }

    /** Opens a shell, runs [script], snapshots the live emulator, then runs [quit]. */
    private fun scenario(name: String, script: (LiveTerminal) -> Unit, quit: (LiveTerminal) -> Unit = {}): TerminalScreenState {
        LiveTerminal(ssh.session).use { term ->
            term.settle()
            term.sendAndSettle("export LANG=en_US.UTF-8 LC_ALL=en_US.UTF-8 PS1='\\$ '; clear\r")
            script(term)
            val (state, raw) = term.snapshot()
            quit(term)
            File(out, "$name.raw").writeBytes(raw)
            File(out, "$name.txt").writeText(state.dump())
            renderPng(state, File(out, "$name.png"))
            val responses = term.responses.toList().joinToString(" ") { it.replace("\u001B", "ESC") }
            spikeResult(LIB, name, "captured", "${raw.size} bytes, responses: ${responses.ifEmpty { "none" }}")
            captured[name] = state
            return state
        }
    }

    @Test @Order(1)
    fun colorsAndUnicode() {
        val state = scenario("colors-unicode", { t ->
            t.sendAndSettle("ls --color=always -la /etc | head -8\r")
            t.sendAndSettle(
                "for i in \$(seq 0 255); do printf '\\e[48;5;%sm%3s\\e[0m' \$i \$i; " +
                    "[ \$(( (i+1) % 32 )) -eq 0 ] && echo; done\r",
            )
            t.sendAndSettle(
                "awk 'BEGIN{for(i=0;i<96;i++){r=255-int(i*255/95);g=int(i*510/95);if(g>255)g=510-g;" +
                    "b=int(i*255/95);printf \"\\033[48;2;%d;%d;%dm \",r,g,b};print \"\\033[0m\"}'\r",
            )
            t.sendAndSettle("printf 'Perché così? Città, però, più | \\xf0\\x9f\\x98\\x80 \\xf0\\x9f\\x9a\\x80 ✓ | 中文字符 日本語 한국어 |\\n'\r")
            t.sendAndSettle("printf '\\e[1mbold\\e[0m \\e[3mitalic\\e[0m \\e[4munderline\\e[0m \\e[7mreverse\\e[0m \\e[9mstrike\\e[0m \\e[2mdim\\e[0m\\n'\r")
        })
        assertTrue("Perché così?" in state.dump(), "accented text missing")
    }

    @Test @Order(2)
    fun htop() {
        val state = scenario("htop", { t ->
            t.send("htop -d 10\r")
            Thread.sleep(2500)
        }, { t -> t.sendAndSettle("q") })
        assertTrue("Load average" in state.dump() || "Mem" in state.dump(), "htop header missing")
    }

    @Test @Order(3)
    fun vim() {
        val state = scenario("vim", { t ->
            t.sendAndSettle("vim -c 'syntax on' -c 'set number' /etc/ssh/sshd_config\r", quietMs = 800)
        }, { t -> t.sendAndSettle("\u001B:q!\r") })
        assertTrue("sshd_config" in state.dump(), "vim status line missing")
    }

    @Test @Order(4)
    fun tmux() {
        val state = scenario("tmux", { t ->
            t.send("tmux new-session -s spike top \\; split-window -h\r")
            Thread.sleep(2500)
            t.sendAndSettle("printf 'pane two: \\xe2\\x94\\x82 ok\\n'\r")
        }, { t -> t.sendAndSettle("tmux kill-server\r") })
        assertTrue("[spike]" in state.dump(), "tmux status bar missing")
    }

    @Test @Order(5)
    fun mc() {
        val state = scenario("mc", { t ->
            t.send("mc\r")
            Thread.sleep(2500)
        }, { t -> t.sendAndSettle("\u001B[21~", quietMs = 600) })
        assertTrue("Left" in state.dump() || "File" in state.dump(), "mc menu bar missing")
    }

    @Test @Order(6)
    fun utf8SplitAcrossChunks() {
        val text = "Perché 😀 中文 ok"
        val emulator = TerminalEmulator(terminalConfig { initialRows = 3; initialCols = 40 })
        text.encodeToByteArray().forEach { emulator.processOutputSync(byteArrayOf(it)) }
        val row = emulator.snapshotScreenState().rows[0]
        val rendered = row.joinToString("") { it.glyphString() }.trimEnd()
        val wide = row.filter { it.width == 2 }.joinToString("") { it.glyphString() }
        spikeResult(LIB, "utf8-split", if (rendered == text) "ok" else "FAIL", "got '$rendered', wide cells '$wide'")
        assertEquals(text, rendered)
        assertEquals("😀中文", wide)
    }

    @Test @Order(7)
    fun resizeReflow() {
        val emulator = TerminalEmulator(terminalConfig { initialRows = 5; initialCols = 100 })
        emulator.processOutputSync("x".repeat(150))
        emulator.resizeSync(5, 160)
        val firstRow = emulator.snapshotScreenState().rows[0].count { it.glyphString() == "x" }
        spikeResult(LIB, "resize-reflow", if (firstRow == 150) "reflows" else "no reflow", "row 0 holds $firstRow of 150 chars after 100->160 cols")
    }

    @Test @Order(8)
    fun throughput() {
        val line = buildString {
            for (i in 0 until 8) append("\u001B[3${i}mcol$i\u001B[0m ")
            append("\u001B[1mbold\u001B[22m plain text to fill the line with ordinary words 0123456789\r\n")
        }.encodeToByteArray()
        val chunk = ByteArray(16 * 1024).also { buf -> for (i in buf.indices) buf[i] = line[i % line.size] }
        val totalChunks = 20 * 1024 * 1024 / chunk.size

        fun run(headless: Boolean, chunks: Int): Double {
            val emulator = TerminalEmulator(terminalConfig { initialRows = 30; initialCols = 100; maxScrollback = 2000 })
            emulator.headlessMode = headless
            val start = System.nanoTime()
            repeat(chunks) { emulator.processOutputSync(chunk) }
            val seconds = (System.nanoTime() - start) / 1e9
            return chunks * chunk.size / 1024.0 / 1024.0 / seconds
        }

        run(true, 128)
        run(false, 128)
        val headless = run(true, totalChunks)
        val withSnapshots = run(false, totalChunks)
        spikeResult(LIB, "throughput", "measured", "20 MB SGR text: %.1f MB/s parse only, %.1f MB/s with a screen snapshot per 16 KB chunk".format(headless, withSnapshots))
    }

    @Test @Order(9)
    fun renderTime() {
        val states = listOfNotNull(captured["htop"], captured["vim"], captured["mc"])
        if (states.size < 2) return
        val ms = timeRender(states, frames = 60)
        spikeResult(LIB, "render-time", "measured", "%.1f ms per 100x30 frame at 2x density (alternating full screens)".format(ms))
    }
}
