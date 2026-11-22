package com.astraconnect.app.widget

import android.view.View
import android.widget.RemoteViews
import com.astraconnect.app.R
import com.astraconnect.app.data.VehicleProfile
import com.astraconnect.app.data.BatteryUse
import com.astraconnect.app.data.EnergyLog
import com.astraconnect.app.data.LatLon
import com.astraconnect.app.data.Place
import com.astraconnect.app.data.PlaceBook
import com.astraconnect.app.data.Stores
import com.astraconnect.app.data.Trip
import com.astraconnect.app.ui.km
import com.astraconnect.app.ui.num
import com.astraconnect.app.ui.pretty
import com.astraconnect.app.ui.time
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Fahrt-Widget: letzte Fahrt mit Strecke, Kraftstoff- und (berechnetem) Stromverbrauch – je nach Antrieb. */
class TripWidget : BaseWidget() {
    companion object {
        private val dayFormat = DateTimeFormatter.ofPattern("EE dd.MM.", Locale.GERMANY).withZone(ZoneId.systemDefault())

        private val shortFormat = DateTimeFormatter.ofPattern("EE HH:mm", Locale.GERMANY).withZone(ZoneId.systemDefault())
        private val dateFormat = DateTimeFormatter.ofPattern("dd.MM.", Locale.GERMANY).withZone(ZoneId.systemDefault())

        /** Kompakt (4×1) und ab 2 Zeilen groß – beide Layouts haben dieselben IDs. */
        fun render(src: WidgetSource): List<Variant> {
            val ctx = src.ctx
            val trips = Stores.trips(ctx).load().filter { it.startedAt != null }.sortedBy { it.startedAt }
            val t = trips.lastOrNull()
            // Nachbarfahrten mitgeben: teilen sich Fahrten ein Messpunkt-Paar, wird nach Strecke aufgeteilt
            val use = t?.let { EnergyLog.compute(trips.takeLast(30), Stores.energy(ctx).all(), VehicleProfile.load(ctx).capacityKwh)[it.key] }
            val places = if (t != null) Stores.places(ctx).places() else emptyList()
            return listOf(
                Variant(0f, fill(src, src.views(R.layout.widget_trip_small), t, use, places, compact = true)),
                Variant(TWO_ROWS_DP, fill(src, src.views(R.layout.widget_trip), t, use, places, compact = false)),
            )
        }

        private fun fill(src: WidgetSource, v: RemoteViews, t: Trip?, use: BatteryUse?, places: List<Place>, compact: Boolean): RemoteViews {
            v.setOnClickPendingIntent(R.id.widget_root, src.openApp())
            // Nur die Verbrauchsspalten, die zum Antrieb passen
            val pt = src.profile.powertrain
            v.setViewVisibility(R.id.w_fuel_col, if (pt.hasFuel) View.VISIBLE else View.GONE)
            v.setViewVisibility(R.id.w_kwh_col, if (pt.hasBattery) View.VISIBLE else View.GONE)
            if (t == null) return v

            v.setTextViewText(
                R.id.w_trip_when,
                when {
                    !compact -> "${dayFormat.format(t.startedAt)} · ${t.startedAt.time()}–${t.stoppedAt.time()}"
                    // Innerhalb einer Woche reicht der Wochentag, sonst das Datum
                    java.time.Duration.between(t.startedAt, src.now).toDays() < 6 -> shortFormat.format(t.startedAt)
                    else -> dateFormat.format(t.startedAt)
                },
            )
            v.setTextViewText(
                R.id.w_trip_route,
                "${where(t.startAddress, t.startPos, places)}${if (compact) " → " else "  →  "}${where(t.endAddress, t.endPos, places)}",
            )

            val dist = t.distanceKm ?: if (t.startMileage != null && t.endMileage != null) t.endMileage - t.startMileage else null
            v.setTextViewText(R.id.w_dist, dist.km(1))
            v.setTextViewText(R.id.w_dist_sub, t.duration?.let { "in ${it.pretty()}" } ?: "gefahren")

            val (fuel, fuelSub) = fuelText(t, dist, compact)
            v.setTextViewText(R.id.w_fuel, fuel)
            v.setTextViewText(R.id.w_fuel_sub, fuelSub)

            val (kwh, kwhSub) = powerText(t, use, dist, compact)
            v.setTextViewText(R.id.w_kwh, kwh)
            v.setTextViewText(R.id.w_kwh_sub, kwhSub)
            return v
        }

        /** Kompakt ist die Spalte schmal – die Einheit steht schon im Wert darüber. */
        private fun per100Text(v: Double, unit: String, compact: Boolean) =
            if (compact) "Ø " + v.num(1) + "/100 km" else v.num(1, "$unit/100 km")

        private fun where(address: String?, pos: LatLon?, places: List<Place>): String =
            PlaceBook.placeAt(places, pos)?.name
                ?: address?.takeIf { it.isNotBlank() }
                ?: pos?.let { String.format(Locale.US, "%.3f, %.3f", it.lat, it.lon) }
                ?: "?"

        internal fun fuelText(t: Trip, dist: Double?, compact: Boolean = false): Pair<String, String> {
            val liters = t.fuelLiters ?: return "–" to "Kraftstoff"
            if (liters < 0.005) return "0 l" to "rein elektrisch"
            val per100 = t.avgFuel ?: dist?.takeIf { it > 0.5 }?.let { liters / it * 100 }
            return liters.num(2, "l") to (per100?.let { per100Text(it, "l", compact) } ?: "Kraftstoff")
        }

        internal fun powerText(t: Trip, use: BatteryUse?, dist: Double?, compact: Boolean = false): Pair<String, String> {
            // Bevorzugt der aus dem Ladestand berechnete Verbrauch, sonst der Wert aus der API
            val calc = use?.takeIf { it.kwh != null }
            // Start-Ladestand bekannt, aber noch keine Meldung nach der Fahrt
            val waiting = use != null && use.socStart != null && use.socEnd == null
            val kwh = calc?.kwh ?: t.electricKwh
                ?: return "–" to if (!waiting) "Strom" else if (compact) "noch offen" else "Strom · noch offen"
            val per100 = if (calc != null) calc.per100km(dist) else t.avgElectric
            val prefix = if (calc?.estimated == true) "≈ " else ""
            return prefix + kwh.num(1, "kWh") to (per100?.let { per100Text(it, "kWh", compact) } ?: "Strom")
        }
    }
}
