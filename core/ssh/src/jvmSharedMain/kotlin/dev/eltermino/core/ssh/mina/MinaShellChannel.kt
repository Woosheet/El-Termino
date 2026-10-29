package dev.eltermino.core.ssh.mina

import dev.eltermino.core.ssh.ShellChannel
import dev.eltermino.core.ssh.SshException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.apache.sshd.client.channel.ChannelShell
import java.io.IOException

/**
 * [output] is meant for a single collector. Chunks wait in a small buffer, so a slow
 * collector applies back-pressure through the SSH window instead of growing memory.
 */
internal class MinaShellChannel(
    private val channel: ChannelShell,
    scope: CoroutineScope,
) : ShellChannel {
    private val chunks = Channel<ByteArray>(capacity = 64)
    private val writeLock = Mutex()

    override val output: Flow<ByteArray> = chunks.receiveAsFlow()

    init {
        scope.launch(Dispatchers.IO) {
            val input = channel.invertedOut
            val buffer = ByteArray(16 * 1024)
            try {
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    if (n > 0) chunks.send(buffer.copyOf(n))
                }
            } catch (_: IOException) {
                // The channel or the connection went away; the flow just ends.
            } finally {
                chunks.close()
            }
        }
    }

    override suspend fun write(bytes: ByteArray) = writeLock.withLock {
        withContext(Dispatchers.IO) {
            try {
                channel.invertedIn.write(bytes)
                channel.invertedIn.flush()
            } catch (e: IOException) {
                throw SshException.ChannelFailed("The shell is closed", e)
            }
        }
    }

    override suspend fun resize(cols: Int, rows: Int) {
        withContext(Dispatchers.IO) {
            try {
                channel.sendWindowChange(cols, rows)
            } catch (e: IOException) {
                throw SshException.ChannelFailed("The shell is closed", e)
            }
        }
    }

    override suspend fun close() {
        channel.closeAndAwait()
    }
}
