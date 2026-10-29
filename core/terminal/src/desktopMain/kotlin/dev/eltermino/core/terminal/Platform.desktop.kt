package dev.eltermino.core.terminal

internal actual val isMacOs: Boolean = System.getProperty("os.name").orEmpty().startsWith("Mac")

internal actual val selectsOnPress: Boolean = true
