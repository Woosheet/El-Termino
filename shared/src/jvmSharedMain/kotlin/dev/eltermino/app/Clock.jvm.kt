package dev.eltermino.app

import java.time.LocalTime
import java.time.format.DateTimeFormatter

internal actual fun clockTime(): String = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm"))
