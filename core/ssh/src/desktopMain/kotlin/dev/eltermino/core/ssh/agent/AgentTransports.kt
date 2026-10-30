package dev.eltermino.core.ssh.agent

import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.Channels
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.Path

private const val MAX_REPLY_BYTES = 256 * 1024
private val WINDOWS_PIPES = listOf("""\\.\pipe\openssh-ssh-agent""", """\\.\pipe\pageant.${System.getProperty("user.name")}""")

private class StreamTransport(
    private val input: InputStream,
    private val output: OutputStream,
    private val closeable: AutoCloseable,
) : AgentTransport {
    override fun exchange(request: ByteArray): ByteArray {
        output.write(request)
        output.flush()
        val data = DataInputStream(input)
        val length = data.readInt()
        if (length !in 1..MAX_REPLY_BYTES) throw IOException("Unreasonable SSH agent reply of $length bytes")
        return ByteArray(length).also(data::readFully)
    }

    override fun close() = closeable.close()
}

/** Null when nothing listens at [path]. */
fun openUnixAgent(path: Path): AgentTransport? {
    if (!Files.exists(path)) return null
    return try {
        val channel = SocketChannel.open(StandardProtocolFamily.UNIX)
        channel.connect(UnixDomainSocketAddress.of(path))
        StreamTransport(Channels.newInputStream(channel), Channels.newOutputStream(channel), channel)
    } catch (e: IOException) {
        null
    }
}

/** shortcut: Windows pipes haven't been tried on a real Windows, check with OpenSSH's agent and Pageant. */
private fun openWindowsAgent(): AgentTransport? = WINDOWS_PIPES.firstNotNullOfOrNull { pipe ->
    try {
        val file = RandomAccessFile(pipe, "rw")
        StreamTransport(Channels.newInputStream(file.channel), Channels.newOutputStream(file.channel), file)
    } catch (e: IOException) {
        null
    }
}

/** The agent of the logged-in user, or null when there's none running. */
fun openSystemAgent(): AgentTransport? {
    if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) return openWindowsAgent()
    return System.getenv("SSH_AUTH_SOCK")?.takeIf { it.isNotEmpty() }?.let { openUnixAgent(Path.of(it)) }
}
