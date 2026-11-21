package com.astraconnect.app.widget

import android.widget.RemoteViews
import com.astraconnect.app.R
import com.astraconnect.app.data.RemoteControl
import com.astraconnect.app.data.VehicleStatus
import com.astraconnect.app.ui.km
import com.astraconnect.app.ui.pct
import com.astraconnect.app.ui.pretty
import com.astraconnect.app.ui.time
import java.time.Duration
import java.time.Instant
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Lade-Widget: Akkustand, Ladestatus mit Restladezeit und Reichweite (elektrisch + gesamt inkl. Benzin).
 * Der Klassenname stammt vom früheren Status-Widget und bleibt, damit platzierte Widgets erhalten bleiben.
 */
class StatusWidget : BaseWidget() {
    companion object {
        fun render(src: WidgetSource): RemoteViews {
            val s = src.status
            val v = src.views(R.layout.widget_status)
            val charge = chargeText(s, src.now)

            v.setTextViewText(R.id.w_soc, s?.batteryPercent.pct())
            v.setProgressBar(R.id.w_soc_bar, 100, percent(s?.batteryPercent), false)
            v.setInt(R.id.w_bolt, "setColorFilter", if (charge.charging) COLOR_OK else COLOR_ACCENT)
            v.setTextViewText(R.id.w_charge_state, charge.state)
            v.setTextColor(R.id.w_charge_state, if (charge.charging) COLOR_OK else COLOR_TEXT)
            v.setTextViewText(R.id.w_charge_eta, charge.detail)
            v.setTextViewText(
                R.id.w_range,
                if (s == null) "Reichweite –" else "${s.electricRangeKm.km()} elektrisch · ${s.totalRangeKm.km()} gesamt",
            )
            v.setTextViewText(R.id.w_updated, s?.updatedAt.stand(src.now))
            v.setOnClickPendingIntent(R.id.widget_root, src.openApp())
            return v
        }
    }
}

/** Reichweiten-Widget: Akku- und Tankstand mit Balken und Reichweite (2×1 kompakt, ab 2 Zeilen mit Gesamt). */
class RangeWidget : BaseWidget() {
    companion object {
        fun render(src: WidgetSource): List<Variant> = listOf(
            Variant(0f, fill(src, src.views(R.layout.widget_range_small))),
            Variant(TWO_ROWS_DP, fill(src, src.views(R.layout.widget_range))),
        )

        private fun fill(src: WidgetSource, v: RemoteViews): RemoteViews {
            val s = src.status
            v.setTextViewText(R.id.w_soc, s?.batteryPercent.pct())
            v.setTextViewText(R.id.w_range_el, s?.electricRangeKm.km())
            v.setProgressBar(R.id.w_soc_bar, 100, percent(s?.batteryPercent), false)
            v.setTextViewText(R.id.w_fuel, s?.fuelPercent.pct())
            v.setTextViewText(R.id.w_range_fuel, s?.fuelRangeKm.km())
            v.setProgressBar(R.id.w_fuel_bar, 100, percent(s?.fuelPercent), false)
            v.setTextViewText(
                R.id.w_range_total,
                if (s == null) "Noch keine Daten"
                else listOf("Gesamt ${s.totalRangeKm.km()}", s.updatedAt.stand(src.now)).filter { it.isNotEmpty() }.joinToString(" · "),
            )
            v.setOnClickPendingIntent(R.id.widget_root, src.openApp())
            return v
        }
    }
}

private fun percent(v: Double?) = (v ?: 0.0).roundToInt().coerceIn(0, 100)

internal data class ChargeText(val state: String, val detail: String, val charging: Boolean)

/** Ladestatus für das Widget; die Restzeit bezieht sich auf den Meldezeitpunkt des Autos. */
internal fun chargeText(s: VehicleStatus?, now: Instant): ChargeText {
    if (s == null) return ChargeText("Noch keine Daten", "", false)
    val c = s.charging ?: return ChargeText("Ladestatus unbekannt", "", false)
    if (c.isCharging) {
        val eta = c.remaining?.let { (s.updatedAt ?: now).plus(it) }
        val left = eta?.let { Duration.between(now, it) }
        return when {
            eta == null || left == null -> ChargeText("Lädt", "Restladezeit unbekannt", true)
            left.isNegative || left.isZero -> ChargeText("Lädt · gleich voll", "fertig ca. ${eta.time()} Uhr", true)
            else -> ChargeText("Lädt · noch ${left.pretty()}", "fertig ca. ${eta.time()} Uhr", true)
        }
    }
    val delayed = RemoteControl.ptTime(c.nextDelayedTime)
    return when {
        c.status.equals("Finished", true) -> ChargeText("Ladung abgeschlossen", "", false)
        c.status.equals("Failure", true) -> ChargeText("Ladefehler", "", false)
        c.plugged == true && c.mode.equals("Delayed", true) && delayed != null ->
            ChargeText("Angesteckt", String.format(Locale.GERMANY, "Start um %02d:%02d Uhr", delayed.first, delayed.second), false)
        c.plugged == true -> ChargeText("Angesteckt · lädt nicht", "", false)
        c.plugged == false -> ChargeText("Nicht angesteckt", "", false)
        else -> ChargeText(c.statusLabel, "", false)
    }
}
