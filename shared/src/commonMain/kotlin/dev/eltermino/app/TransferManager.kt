package dev.eltermino.app

import dev.eltermino.core.data.newId
import dev.eltermino.core.ssh.RemoteFile
import dev.eltermino.core.ssh.SshException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/** What to do when the destination already has a file with the same name. */
enum class ConflictPolicy {
    Overwrite,
    /** Continues a shorter file from where it stops; an equal one counts as done. */
    Resume,
    /** Picks a free name: "report (1).pdf". */
    Rename,
    Skip,
}

sealed interface TransferState {
    data object Queued : TransferState
    data object Running : TransferState

    /** A host's connection dropped; the transfer carries on by itself once it's back. */
    data class Waiting(val message: String) : TransferState
    data object Paused : TransferState
    data object Done : TransferState
    data class Failed(val message: String) : TransferState
    data object Cancelled : TransferState

    val isActive: Boolean get() = this is Running || this is Waiting
    val isFinished: Boolean get() = this is Done || this is Failed || this is Cancelled
}

data class TransferProgress(
    val bytesDone: Long = 0,
    val bytesTotal: Long = 0,
    val filesDone: Int = 0,
    val filesTotal: Int = 0,
    val bytesPerSecond: Long = 0,
    val currentFile: String? = null,
    /** Symlinks and special files inside folders, which aren't copied. */
    val skipped: Int = 0,
)

/** Everything in the queue, for the status bar. */
data class TransferSummary(
    val active: Int = 0,
    val waiting: Int = 0,
    val paused: Int = 0,
    val failed: Int = 0,
    val bytesPerSecond: Long = 0,
)

/** One file or folder going from [source] into [targetDir]. */
class Transfer internal constructor(
    val id: String,
    val source: Location,
    val targetDir: Location,
    val name: String,
    val isDirectory: Boolean,
    val policy: ConflictPolicy,
    /** [targetDir] is already the file to write, chosen by the system. */
    internal val exactTarget: Boolean = false,
) {
    internal val mutableState = MutableStateFlow<TransferState>(TransferState.Queued)
    val state: StateFlow<TransferState> = mutableState.asStateFlow()

    internal val mutableProgress = MutableStateFlow(TransferProgress())
    val progress: StateFlow<TransferProgress> = mutableProgress.asStateFlow()

    /** Where it ends up; not [name] when [ConflictPolicy.Rename] had to pick a free one. */
    var targetPath: String = childPath(targetDir.path, name)
        internal set

    internal var steps: List<Step>? = null
    internal var next = 0

    /** The file being written when it stopped: picked up where it left off, never redone. */
    internal var partial = -1
    internal var job: Job? = null

    internal val endpoints get() = setOf(source.endpoint, targetDir.endpoint)
}

internal sealed interface Step {
    data class Mkdir(val path: String) : Step
    data class Copy(val from: String, val to: String, val size: Long, val modified: Long, val permissions: Int) : Step
}

/**
 * One queue for every transfer, whichever hosts it involves: local to remote, remote to
 * local and host to host. Data streams through without temporary files. At most
 * [maxParallel] transfers run at once, and [maxPerHost] on any one host.
 */
class TransferManager(
    private val scope: CoroutineScope,
    private val services: AppServices,
    private val connections: Connections,
    val maxPerHost: Int = 2,
) {
    private val mutableTransfers = MutableStateFlow<List<Transfer>>(emptyList())
    val transfers: StateFlow<List<Transfer>> = mutableTransfers.asStateFlow()

    val maxParallel = MutableStateFlow(3)

    @OptIn(ExperimentalCoroutinesApi::class)
    val summary: StateFlow<TransferSummary> = transfers
        .flatMapLatest { list ->
            if (list.isEmpty()) flowOf(TransferSummary())
            else combine(list.map { t -> combine(t.state, t.progress) { s, p -> s to p } }) { pairs ->
                val active = pairs.filter { it.first.isActive }
                TransferSummary(
                    active = active.size,
                    waiting = pairs.count { it.first is TransferState.Queued },
                    paused = pairs.count { it.first is TransferState.Paused },
                    failed = pairs.count { it.first is TransferState.Failed },
                    bytesPerSecond = active.sumOf { it.second.bytesPerSecond },
                )
            }
        }
        .stateIn(scope, SharingStarted.Eagerly, TransferSummary())

    private val mutableEnded = MutableSharedFlow<Transfer>(extraBufferCapacity = 64)

    /** Each transfer as it stops, for whatever reason: its destination folder may have changed. */
    val ended: SharedFlow<Transfer> = mutableEnded

    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val mutex = Mutex()
    private val remotes = mutableMapOf<String, RemoteFileSystem>()

    init {
        scope.launch { for (signal in wake) schedule() }
        scope.launch { maxParallel.collect { wake.trySend(Unit) } }
    }

    /** Which of [names] [targetDir] already has, so the user can choose a [ConflictPolicy]. */
    suspend fun existing(targetDir: Location, names: List<String>): List<String> = try {
        val fs = fileSystem(targetDir.endpoint)
        names.filter { fs.stat(childPath(targetDir.path, it)) != null }
    } finally {
        releaseIdle()
    }

    /** Where browsing starts on [endpoint]: the login folder of a host, or the home folder here. */
    suspend fun home(endpoint: Endpoint): String = try {
        fileSystem(endpoint).home()
    } finally {
        releaseIdle()
    }

    /** Queues [files], all from [source], to go into [targetDir]. */
    fun enqueue(source: Endpoint, files: List<RemoteFile>, targetDir: Location, policy: ConflictPolicy): List<Transfer> {
        val added = files.map { file ->
            Transfer(newId(), Location(source, file.path), targetDir, file.name, file.isDirectory, policy)
        }
        mutableTransfers.update { it + added }
        wake.trySend(Unit)
        return added
    }

    /** Queues [file] to overwrite [targetPath], on this device, which the user has just chosen. */
    fun saveAs(source: Endpoint, file: RemoteFile, targetPath: String): Transfer {
        val added = Transfer(newId(), Location(source, file.path), Location(Endpoint.Local, targetPath), file.name, false, ConflictPolicy.Overwrite, exactTarget = true)
        mutableTransfers.update { it + added }
        wake.trySend(Unit)
        return added
    }

    fun pause(transfer: Transfer) {
        val state = transfer.state.value
        if (state.isFinished || state is TransferState.Paused) return
        transfer.mutableState.value = TransferState.Paused
        transfer.job?.cancel()
    }

    /** Continues a paused transfer, or tries a failed one again from where it stopped. */
    fun resume(transfer: Transfer) {
        val state = transfer.state.value
        if (state !is TransferState.Paused && state !is TransferState.Failed) return
        transfer.mutableState.value = TransferState.Queued
        wake.trySend(Unit)
    }

    /** Stops for good; the half-written file is removed. */
    fun cancel(transfer: Transfer) {
        if (transfer.state.value.isFinished) return
        val running = transfer.job
        transfer.mutableState.value = TransferState.Cancelled
        running?.cancel()
        val half = transfer.steps?.getOrNull(transfer.partial) as? Step.Copy ?: return
        scope.launch {
            running?.join()
            runCatching { fileSystem(transfer.targetDir.endpoint).delete(half.to, recursive = false) }
            releaseIdle()
        }
    }

    fun clearFinished() = mutableTransfers.update { list -> list.filterNot { it.state.value is TransferState.Done || it.state.value is TransferState.Cancelled } }

    private fun schedule() {
        val list = mutableTransfers.value
        val active = list.filter { it.state.value.isActive }.toMutableList()
        for (transfer in list) {
            if (transfer.state.value != TransferState.Queued) continue
            if (active.size >= maxParallel.value) break
            val hostFull = transfer.endpoints.any { endpoint ->
                endpoint is Endpoint.Remote && active.count { endpoint in it.endpoints } >= maxPerHost
            }
            if (hostFull) continue
            start(transfer)
            active += transfer
        }
    }

    private fun start(transfer: Transfer) {
        transfer.mutableState.value = TransferState.Running
        transfer.job = scope.launch {
            try {
                run(transfer)
                transfer.mutableState.value = TransferState.Done
            } catch (e: CancellationException) {
                // pause() and cancel() already said why.
                throw e
            } catch (e: Throwable) {
                transfer.mutableState.value = TransferState.Failed(failureMessage(e))
            } finally {
                mutableEnded.tryEmit(transfer)
                withContext(NonCancellable) { releaseIdle() }
                wake.trySend(Unit)
            }
        }
    }

    private suspend fun run(transfer: Transfer) {
        var attempts = 0
        while (true) {
            val source = fileSystem(transfer.source.endpoint)
            val target = fileSystem(transfer.targetDir.endpoint)
            try {
                val steps = transfer.steps ?: plan(transfer, source, target).also { transfer.steps = it }
                copy(transfer, steps, source, target)
                return
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                // A dropped connection is waited out; anything else is a real failure.
                val down = listOf(source, target).filterIsInstance<RemoteFileSystem>().firstOrNull { it.lostItsLink() }
                if (down == null || ++attempts > 5) throw e
                transfer.mutableState.value = TransferState.Waiting("Connessione a ${down.host.label} persa: riprendo quando torna")
                val state = down.link.state.first { it is LinkState.Up || it is LinkState.Down }
                if (state is LinkState.Down) throw SshException.ChannelFailed(state.message)
                transfer.mutableState.value = TransferState.Running
            }
        }
    }

    /** The link reports a drop a moment after the stream failed, hence the short wait. */
    private suspend fun RemoteFileSystem.lostItsLink(): Boolean =
        withTimeoutOrNull(2.seconds) { link.state.first { it !is LinkState.Up } } != null

    private suspend fun plan(transfer: Transfer, source: FileSystem, target: FileSystem): List<Step> {
        val item = source.stat(transfer.source.path) ?: throw IllegalStateException("${transfer.name} non c'è più")
        var destination = if (transfer.exactTarget) transfer.targetDir.path else childPath(transfer.targetDir.path, transfer.name)
        if (transfer.policy == ConflictPolicy.Rename && target.stat(destination) != null) {
            destination = freeName(target, transfer.targetDir.path, transfer.name)
        }
        transfer.targetPath = destination
        val steps = mutableListOf<Step>()
        var skipped = 0

        suspend fun walk(file: RemoteFile, to: String) {
            when (file.type) {
                RemoteFile.Type.Directory -> {
                    steps += Step.Mkdir(to)
                    for (child in source.list(file.path)) walk(child, childPath(to, child.name))
                }
                RemoteFile.Type.File -> steps += Step.Copy(file.path, to, file.size, file.modifiedEpochSeconds, file.permissions)
                // Following links could loop or leave the folder; they're left out.
                else -> skipped++
            }
        }
        walk(item, destination)
        val copies = steps.filterIsInstance<Step.Copy>()
        transfer.mutableProgress.value = TransferProgress(bytesTotal = copies.sumOf { it.size }, filesTotal = copies.size, skipped = skipped)
        return steps
    }

    private suspend fun copy(transfer: Transfer, steps: List<Step>, source: FileSystem, target: FileSystem) {
        val before = steps.take(transfer.next).filterIsInstance<Step.Copy>()
        val meter = Meter(transfer, bytesDone = before.sumOf { it.size }, filesDone = before.size)
        for (index in transfer.next until steps.size) {
            when (val step = steps[index]) {
                is Step.Mkdir -> if (target.stat(step.path) == null) target.mkdir(step.path)
                is Step.Copy -> copyFile(transfer, index, step, source, target, meter)
            }
            transfer.next = index + 1
        }
        meter.flush()
    }

    private suspend fun copyFile(transfer: Transfer, index: Int, step: Step.Copy, source: FileSystem, target: FileSystem, meter: Meter) {
        val existing = target.stat(step.to)
        var to = step.to
        val offset = when {
            index == transfer.partial -> existing?.size?.coerceAtMost(step.size) ?: 0
            existing == null -> 0
            transfer.policy == ConflictPolicy.Skip -> return meter.fileDone(step.size)
            transfer.policy == ConflictPolicy.Resume -> if (existing.size <= step.size) existing.size else 0
            transfer.policy == ConflictPolicy.Rename -> {
                to = freeName(target, parentPath(step.to), fileName(step.to))
                0
            }
            else -> 0
        }
        // Size 0 may mean unknown, so nothing there says it's complete.
        if (existing != null && step.size > 0 && offset == step.size && (index == transfer.partial || transfer.policy == ConflictPolicy.Resume)) {
            transfer.partial = -1
            return meter.fileDone(step.size)
        }
        transfer.partial = index
        meter.startFile(fileName(step.from), offset)
        val input = source.openRead(step.from, offset)
        val output = try {
            target.openWrite(to, offset)
        } catch (e: Throwable) {
            withContext(NonCancellable) { runCatching { input.close() } }
            throw e
        }
        try {
            val buffer = ByteArray(256 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                if (n == 0) continue
                output.write(buffer, n)
                meter.add(n.toLong())
                currentCoroutineContext().ensureActive()
            }
        } finally {
            // A pause lands between two publishes; show the bytes that really are there.
            meter.publish()
            withContext(NonCancellable) {
                runCatching { input.close() }
                runCatching { output.close() }
            }
        }
        transfer.partial = -1
        // A date of 0 is one the source doesn't know, not 1970.
        if (step.modified != 0L) runCatching { target.setModified(to, step.modified) }
        if (step.permissions != 0) runCatching { target.chmod(to, step.permissions) }
        meter.fileDone(0)
    }

    private suspend fun freeName(fs: FileSystem, dir: String, name: String): String {
        val dot = name.lastIndexOf('.').takeIf { it > 0 } ?: name.length
        val base = name.substring(0, dot)
        val extension = name.substring(dot)
        var n = 1
        while (true) {
            val candidate = childPath(dir, "$base ($n)$extension")
            if (fs.stat(candidate) == null) return candidate
            n++
        }
    }

    private suspend fun fileSystem(endpoint: Endpoint): FileSystem = when (endpoint) {
        Endpoint.Local -> services.localFiles ?: throw IllegalStateException("Su questo dispositivo non ci sono file locali")
        is Endpoint.Remote -> mutex.withLock {
            remotes[endpoint.hostId]?.takeIf { it.link.state.value !is LinkState.Down } ?: run {
                remotes.remove(endpoint.hostId)?.release()
                val host = services.workspace.workspace.value.host(endpoint.hostId) ?: throw HostMissingException()
                connections.files(host).also { remotes[endpoint.hostId] = it }
            }
        }
    }

    /** Lets go of the links no queued or running transfer needs. */
    private suspend fun releaseIdle() = mutex.withLock {
        val busy = mutableTransfers.value
            .filter { it.state.value == TransferState.Queued || it.state.value.isActive }
            .flatMap { it.endpoints }
            .filterIsInstance<Endpoint.Remote>()
            .map { it.hostId }
            .toSet()
        val idle = remotes.keys - busy
        idle.forEach { remotes.remove(it)?.release() }
    }

    /** Progress and speed, published a few times a second rather than per buffer. */
    private class Meter(private val transfer: Transfer, private var bytesDone: Long, private var filesDone: Int) {
        private val clock = TimeSource.Monotonic
        private var lastPublish = clock.markNow()
        private var windowStart = clock.markNow()
        private var windowBytes = 0L
        private var speed = 0L
        private var current: String? = null

        fun startFile(name: String, offset: Long) {
            current = name
            bytesDone += offset
            publish()
        }

        fun add(bytes: Long) {
            bytesDone += bytes
            windowBytes += bytes
            val window = windowStart.elapsedNow()
            if (window.inWholeMilliseconds >= 500) {
                val instant = windowBytes * 1000 / window.inWholeMilliseconds
                speed = if (speed == 0L) instant else (speed * 6 + instant * 4) / 10
                windowStart = clock.markNow()
                windowBytes = 0
            }
            if (lastPublish.elapsedNow().inWholeMilliseconds >= 100) publish()
        }

        fun fileDone(skippedBytes: Long) {
            bytesDone += skippedBytes
            filesDone++
            publish()
        }

        fun flush() {
            speed = 0
            current = null
            publish()
        }

        fun publish() {
            lastPublish = clock.markNow()
            transfer.mutableProgress.update {
                it.copy(bytesDone = bytesDone, filesDone = filesDone, bytesPerSecond = speed, currentFile = current)
            }
        }
    }
}
