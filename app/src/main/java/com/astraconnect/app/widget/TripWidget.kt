package com.astraconnect.app.widget

import android.widget.RemoteViews
import com.astraconnect.app.R
import com.astraconnect.app.data.AppSettings
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

/** Fahrt-Widget: letzte Fahrt mit Strecke, Benzin- und (berechnetem) Stromverbrauch. */
class TripWidget : BaseWidget() {
    companion object {
        private val dayFormat = DateTimeFormatter.ofPattern("EE dd.MM.", Locale.GERMANY).withZone(ZoneId.systemDefault())

        fun render(src: WidgetSource): RemoteViews {
            val ctx = src.ctx
            val v = src.views(R.layout.widget_trip)
            v.setOnClickPendingIntent(R.id.widget_root, src.openApp())

            val trips = Stores.trips(ctx).load().filter { it.startedAt != null }.sortedBy { it.startedAt }
            val t = trips.lastOrNull() ?: return v
            // Nachbarfahrten mitgeben: teilen sich Fahrten ein Messpunkt-Paar, wird nach Strecke aufgeteilt
            val use = EnergyLog.compute(trips.takeLast(30), Stores.energy(ctx).all(), AppSettings(ctx).capacityKwh)[t.key]
            val places = Stores.places(ctx).places()

            v.setTextViewText(
                R.id.w_trip_when,
                "${dayFormat.format(t.startedAt)} · ${t.startedAt.time()}–${t.stoppedAt.time()}",
            )
            v.setTextViewText(R.id.w_trip_route, "${where(t.startAddress, t.startPos, places)}  →  ${where(t.endAddress, t.endPos, places)}")

            val dist = t.distanceKm ?: if (t.startMileage != null && t.endMileage != null) t.endMileage - t.startMileage else null
            v.setTextViewText(R.id.w_dist, dist.km(1))
            v.setTextViewText(R.id.w_dist_sub, t.duration?.let { "in ${it.pretty()}" } ?: "gefahren")

            val (fuel, fuelSub) = fuelText(t, dist)
            v.setTextViewText(R.id.w_fuel, fuel)
            v.setTextViewText(R.id.w_fuel_sub, fuelSub)

            val (kwh, kwhSub) = powerText(t, use, dist)
            v.setTextViewText(R.id.w_kwh, kwh)
            v.setTextViewText(R.id.w_kwh_sub, kwhSub)
            return v
        }

        private fun where(address: String?, pos: LatLon?, places: List<Place>): String =
            PlaceBook.placeAt(places, pos)?.name
                ?: address?.takeIf { it.isNotBlank() }
                ?: pos?.let { String.format(Locale.US, "%.3f, %.3f", it.lat, it.lon) }
                ?: "?"

        internal fun fuelText(t: Trip, dist: Double?): Pair<String, String> {
            val liters = t.fuelLiters ?: return "–" to "Benzin"
            if (liters < 0.005) return "0 l" to "rein elektrisch"
            val per100 = t.avgFuel ?: dist?.takeIf { it > 0.5 }?.let { liters / it * 100 }
            return liters.num(2, "l") to (per100?.let { it.num(1, "l/100 km") } ?: "Benzin")
        }

        internal fun powerText(t: Trip, use: BatteryUse?, dist: Double?): Pair<String, String> {
            // Bevorzugt der aus dem Ladestand berechnete Verbrauch, sonst der Wert aus der API
            val calc = use?.takeIf { it.kwh != null }
            val kwh = calc?.kwh ?: t.electricKwh ?: return "–" to "Strom"
            val per100 = if (calc != null) calc.per100km(dist) else t.avgElectric
            val prefix = if (calc?.shared == true) "≈ " else ""
            return prefix + kwh.num(1, "kWh") to (per100?.let { it.num(1, "kWh/100 km") } ?: "Strom")
        }
    }
}
