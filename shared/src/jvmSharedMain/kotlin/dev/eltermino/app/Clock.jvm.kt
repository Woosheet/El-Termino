package dev.eltermino.app

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

internal actual fun clockTime(): String = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm"))

private val italian = Locale.ITALIAN

actual fun formatFileTime(epochSeconds: Long): String {
    if (epochSeconds <= 0) return ""
    val time = Instant.ofEpochSecond(epochSeconds).atZone(ZoneId.systemDefault())
    val today = LocalDate.now()
    val pattern = when {
        time.toLocalDate() == today -> "HH:mm"
        time.year == today.year -> "d MMM HH:mm"
        else -> "d MMM yyyy"
    }
    return time.format(DateTimeFormatter.ofPattern(pattern, italian))
}
