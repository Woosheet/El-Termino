package dev.eltermino.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.draganddrop.dragAndDropSource
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.DragAndDropTransferAction
import androidx.compose.ui.draganddrop.DragAndDropTransferData
import androidx.compose.ui.draganddrop.DragAndDropTransferable
import androidx.compose.ui.draganddrop.DragData
import androidx.compose.ui.draganddrop.dragData
import dev.eltermino.app.FileBrowser
import dev.eltermino.app.FilesForDrag
import dev.eltermino.app.dragOutProblem
import dev.eltermino.core.ssh.RemoteFile
import java.io.File
import java.net.URI

@OptIn(ExperimentalFoundationApi::class, ExperimentalComposeUiApi::class)
internal actual fun Modifier.onFilesDropped(onHover: (Boolean) -> Unit, onDrop: (List<String>) -> Unit): Modifier = composed {
    val currentDrop by rememberUpdatedState(onDrop)
    val currentHover by rememberUpdatedState(onHover)
    val target = remember {
        object : DragAndDropTarget {
            override fun onEntered(event: DragAndDropEvent) = currentHover(true)
            override fun onExited(event: DragAndDropEvent) = currentHover(false)
            override fun onEnded(event: DragAndDropEvent) = currentHover(false)

            override fun onDrop(event: DragAndDropEvent): Boolean {
                val files = event.dragData() as? DragData.FilesList ?: return false
                currentDrop(files.readFiles().map { File(URI(it)).absolutePath.replace('\\', '/') })
                return true
            }
        }
    }
    dragAndDropTarget(shouldStartDragAndDrop = { it.dragData() is DragData.FilesList }, target = target)
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalComposeUiApi::class)
internal actual fun Modifier.dragToSystem(browser: FileBrowser, items: () -> List<RemoteFile>): Modifier = composed {
    val currentItems by rememberUpdatedState(items)
    dragAndDropSource { _ ->
        val entries = currentItems()
        val problem = dragOutProblem(entries)
        if (problem != null) {
            browser.notify(problem)
            null
        } else {
            DragAndDropTransferData(
                DragAndDropTransferable(FilesForDrag(browser::fileSystem, entries.map { it.path })),
                listOf(DragAndDropTransferAction.Copy),
            )
        }
    }
}
