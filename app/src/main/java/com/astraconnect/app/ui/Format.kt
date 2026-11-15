package com.astraconnect.app.ui

import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val dateTime = DateTimeFormatter.ofPattern("dd.MM.yyyy, HH:mm").withZone(ZoneId.systemDefault())
private val dateOnly = DateTimeFormatter.ofPattern("EE, dd.MM.yyyy", Locale.GERMANY).withZone(ZoneId.systemDefault())
private val timeOnly = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault())

fun Instant?.dateTime(): String = this?.let(dateTime::format) ?: "–"
fun Instant?.dateLong(): String = this?.let(dateOnly::format) ?: "–"
fun Instant?.time(): String = this?.let(timeOnly::format) ?: "–"

fun Double?.km(digits: Int = 0): String =
    this?.let { String.format(Locale.GERMANY, "%,.${digits}f km", it) } ?: "–"

fun Double?.pct(): String = this?.let { String.format(Locale.GERMANY, "%.0f %%", it) } ?: "–"

fun Double?.num(digits: Int = 1, unit: String = ""): String =
    this?.let { String.format(Locale.GERMANY, "%.${digits}f", it) + if (unit.isNotEmpty()) " $unit" else "" } ?: "–"

fun Duration?.pretty(): String {
    if (this == null) return "–"
    val h = toHours()
    val m = toMinutes() % 60
    return if (h > 0) "$h h ${"%02d".format(m)} min" else "$m min"
}

fun Instant?.ago(): String {
    if (this == null) return "unbekannt"
    val d = Duration.between(this, Instant.now())
    return when {
        d.isNegative || d.toMinutes() < 1 -> "gerade eben"
        d.toMinutes() < 60 -> "vor ${d.toMinutes()} Min."
        d.toHours() < 24 -> "vor ${d.toHours()} Std."
        else -> "vor ${d.toDays()} Tag(en)"
    }
}
