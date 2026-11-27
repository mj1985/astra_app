package com.astraconnect.app.data

import android.content.Context
import android.location.Geocoder
import java.util.Locale

/**
 * Ergänzt fehlende Start- und Zieladressen von Fahrten per Reverse-Geocoding des Geräts.
 * Wird überall aufgerufen, wo Fahrten vom Server übernommen werden (Abruf, Hintergrund-Job),
 * damit nicht erst nach einem erneuten Laden eine Adresse statt der Koordinaten erscheint.
 * myOpel-Importe bringen ihre Adresse schon mit – sie werden hier übersprungen.
 */
object TripGeocoder {
    /** Höchstens so viele Fahrten je Durchgang (das Gerät drosselt sonst). */
    private const val MAX_PER_RUN = 40

    /**
     * Füllt fehlende Adressen im [store]. Läuft synchron und blockierend – der Aufrufer sorgt für
     * einen Hintergrund-Thread. Liefert true, wenn mindestens eine Adresse ergänzt wurde.
     */
    fun resolveMissing(ctx: Context, store: TripStore): Boolean {
        if (!Geocoder.isPresent()) return false
        val geocoder = Geocoder(ctx, Locale.GERMANY)
        val todo = store.loadSingles().filter {
            (it.startAddress == null && it.startPos != null) || (it.endAddress == null && it.endPos != null)
        }.take(MAX_PER_RUN)
        var changed = false
        for (t in todo) {
            val s = if (t.startAddress == null) t.startPos?.let { address(geocoder, it) } else null
            val e = if (t.endAddress == null) t.endPos?.let { address(geocoder, it) } else null
            if (s != null || e != null) {
                store.update(t.key, startAddress = s, endAddress = e)
                changed = true
            }
        }
        return changed
    }

    @Suppress("DEPRECATION")
    private fun address(geocoder: Geocoder, p: LatLon): String? = runCatching {
        geocoder.getFromLocation(p.lat, p.lon, 1)?.firstOrNull()?.let { a ->
            listOfNotNull(
                listOfNotNull(a.thoroughfare, a.subThoroughfare).joinToString(" ").ifBlank { null },
                listOfNotNull(a.postalCode, a.locality).joinToString(" ").ifBlank { null },
            ).joinToString(", ").ifBlank { a.getAddressLine(0) }
        }
    }.getOrNull()
}
