package dev.eltermino.app

import dev.eltermino.core.data.FileWorkspaceStore
import dev.eltermino.core.ssh.agent.AgentTransport
import dev.eltermino.core.ssh.mina.MinaKeyTool
import dev.eltermino.core.ssh.mina.MinaSshConnector
import dev.eltermino.core.vault.EncryptedFileVault
import dev.eltermino.core.vault.MasterKeyProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The real services, as both apps use them: everything lives under [dataDir]. The local
 * file panel starts at [localFilesRoot].
 */
fun createAppServices(
    dataDir: File,
    masterKey: MasterKeyProvider,
    platform: Platform,
    localFilesRoot: File? = null,
    sshAgent: (() -> AgentTransport?)? = null,
    /** Adds to the local files what's reached differently on this system. */
    wrapLocalFiles: (FileSystem) -> FileSystem = { it },
): AppServices = AppServices(
    workspace = FileWorkspaceStore(File(dataDir, "workspace.json")),
    vault = EncryptedFileVault(File(dataDir, "vault.bin"), masterKey),
    connector = MinaSshConnector(agent = sshAgent),
    keyTool = MinaKeyTool(),
    platform = platform,
    localFiles = localFilesRoot?.let { wrapLocalFiles(LocalFileSystem(it)) },
    recordings = FileRecordingStore(File(dataDir, "recordings")),
    saveSafetyCopy = { bytes ->
        withContext(Dispatchers.IO) {
            val dir = File(dataDir, "backups").apply { mkdirs() }
            File(dir, "prima-del-ripristino-${System.currentTimeMillis()}.etbackup").writeBytes(bytes)
        }
    },
    readSafetyCopy = {
        withContext(Dispatchers.IO) { File(dataDir, "backups").listFiles().orEmpty().maxByOrNull { it.name }?.readBytes() }
    },
)
