package com.astraconnect.app.wear

import android.content.Context
import android.util.Log
import com.astraconnect.app.data.Garage
import com.astraconnect.app.data.VehicleStatus
import com.astraconnect.app.ui.km
import com.astraconnect.app.ui.time
import com.astraconnect.app.widget.PendingCommands
import com.astraconnect.app.widget.WidgetSource
import com.astraconnect.app.widget.chargeText
import com.astraconnect.shared.WearContract
import com.astraconnect.shared.WearStatus
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import java.time.Instant
import kotlin.math.roundToInt

/**
 * Schickt den Stand des in der App gewählten Fahrzeugs an die Uhr-App (Tile und Complication).
 * Läuft bei jedem Widget-Update mit; gleiche Daten überträgt die Data Layer API nicht erneut.
 * Ohne Google-Play-Dienste oder ohne Uhr schlägt das still fehl.
 */
object WearSync {
    private const val TAG = "WearSync"

    fun status(ctx: Context): WearStatus {
        val src = WidgetSource(ctx, Garage.selectedId(ctx))
        val s = src.status
        val pt = src.profile.powertrain
        val charge = chargeText(s, src.now)
        val prefix = Garage.safe(src.vehicleId) + "/"
        return WearStatus(
            vehicleId = src.vehicleId,
            vehicleName = src.name,
            hasBattery = pt.hasBattery,
            hasClimate = pt.hasRemoteClimate,
            batteryPercent = s?.batteryPercent?.roundToInt()?.coerceIn(0, 100),
            fuelPercent = s?.fuelPercent?.roundToInt()?.coerceIn(0, 100),
            rangeText = when {
                s == null -> "–"
                pt.hasBattery && pt.hasFuel -> "${s.electricRangeKm.km()} el. · ${s.totalRangeKm.km()}"
                pt.hasBattery -> s.electricRangeKm.km()
                else -> s.totalRangeKm.km()
            },
            chargeText = if (pt.hasBattery) shortCharge(s, src.now) else "",
            charging = charge.charging,
            locked = s?.isLocked,
            climateOn = s?.preconditioning.equals("Enabled", true),
            updatedAt = s?.updatedAt?.toEpochMilli(),
            sending = PendingCommands.get(src.prefs).filter { it.startsWith(prefix) }.map { it.removePrefix(prefix) }.toSet(),
            vehicleCount = Garage.vehicles(ctx).size.coerceAtLeast(1),
        )
    }

    /**
     * Ladestatus kurz genug für den unteren Rand einer runden Uhr (≈ 15 Zeichen), z. B. „Lädt bis 14:35“.
     * Die Restzeit bezieht sich wie im Lade-Widget auf den Meldezeitpunkt des Autos.
     */
    internal fun shortCharge(s: VehicleStatus?, now: Instant): String {
        val c = s?.charging ?: return ""
        if (!c.isCharging) return c.statusLabel
        val eta = c.remaining?.let { (s.updatedAt ?: now).plus(it) }
        return when {
            eta == null -> "Lädt"
            !eta.isAfter(now) -> "Gleich voll"
            else -> "Lädt bis ${eta.time()}"
        }
    }

    fun push(ctx: Context) {
        val req = PutDataMapRequest.create(WearContract.PATH_STATUS).apply {
            dataMap.putString(WearContract.KEY_JSON, status(ctx).toJson())
        }.asPutDataRequest().setUrgent()
        Wearable.getDataClient(ctx).putDataItem(req)
            .addOnFailureListener { Log.d(TAG, "Keine Uhr erreichbar: ${it.message}") }
    }
}
