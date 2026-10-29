package dev.eltermino.ui.components

import androidx.compose.ui.input.pointer.PointerIcon
import java.awt.Cursor

internal actual val HorizontalResizeIcon: PointerIcon = PointerIcon(Cursor(Cursor.E_RESIZE_CURSOR))
internal actual val VerticalResizeIcon: PointerIcon = PointerIcon(Cursor(Cursor.N_RESIZE_CURSOR))
