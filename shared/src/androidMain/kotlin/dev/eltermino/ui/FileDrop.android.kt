package dev.eltermino.ui

import androidx.compose.ui.Modifier

// Files come from the system picker on Android, not from drag and drop.
internal actual fun Modifier.onFilesDropped(onHover: (Boolean) -> Unit, onDrop: (List<String>) -> Unit): Modifier = this
