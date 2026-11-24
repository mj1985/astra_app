package com.astraconnect.app.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AttachMoney
import androidx.compose.material.icons.filled.CurrencyFranc
import androidx.compose.material.icons.filled.CurrencyLira
import androidx.compose.material.icons.filled.CurrencyRuble
import androidx.compose.material.icons.filled.CurrencyYen
import androidx.compose.material.icons.filled.Euro
import androidx.compose.material.icons.filled.Payments
import androidx.compose.ui.graphics.vector.ImageVector
import com.astraconnect.app.data.Currency
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

/** Symbol der Währung des gewählten Markts, z. B. für Feldbeschriftungen („Betrag €“). */
val currencySymbol: String get() = Currency.current.symbol

/** Betrag in der Währung des gewählten Markts, z. B. „1.234,56 €“ oder „1.234 Ft“. */
fun Double?.money(): String = this?.let {
    String.format(Locale.GERMANY, "%,.${Currency.current.digits}f", it) + " " + Currency.current.symbol
} ?: "–"

/** Preis je Einheit, z. B. „1,799 €/l“ oder „0,350 CHF/kWh“. */
fun Double?.unitPrice(unit: String, digits: Int = Currency.current.priceDigits): String =
    num(digits, "${Currency.current.symbol}/$unit")

/** Passendes Symbol-Icon zur Landeswährung (allgemeines Geld-Icon, wo es kein eigenes gibt). */
fun currencyIcon(): ImageVector = when (Currency.current) {
    Currency.EUR -> Icons.Filled.Euro
    Currency.CHF -> Icons.Filled.CurrencyFranc
    Currency.JPY -> Icons.Filled.CurrencyYen
    Currency.RUB -> Icons.Filled.CurrencyRuble
    Currency.TRY -> Icons.Filled.CurrencyLira
    Currency.NZD, Currency.SGD, Currency.TWD -> Icons.Filled.AttachMoney
    else -> Icons.Filled.Payments
}

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
