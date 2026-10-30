package dev.eltermino.ui

import androidx.compose.ui.Modifier
import dev.eltermino.app.FileBrowser
import dev.eltermino.core.ssh.RemoteFile

// Files come from the system picker on Android, not from drag and drop.
internal actual fun Modifier.onFilesDropped(onHover: (Boolean) -> Unit, onDrop: (List<String>) -> Unit): Modifier = this

internal actual fun Modifier.dragToSystem(browser: FileBrowser, items: () -> List<RemoteFile>): Modifier = this
