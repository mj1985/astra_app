package com.astraconnect.app.data

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.Instant
import java.time.YearMonth

data class RestoreResult(val trips: Int, val snapshots: Int, val vehicles: Int = 1) {
    val summary get() = "$trips Fahrt(en) übernommen/ergänzt, $snapshots Messpunkt(e) ergänzt" +
        (if (vehicles > 1) " für $vehicles Fahrzeuge" else "") +
        ". Orte, Regeln, Tank-/Ladekosten und Einstellungen eingespielt."
}

/**
 * Datensicherung in einer JSON-Datei: Orte & Regeln und gemeinsame Einstellungen, dazu je Fahrzeug
 * Fahrtenbuch (inkl. Kategorien, Notizen, Importe), Ladestand-Messpunkte, Tank-/Ladekosten und
 * Fahrzeug-Einstellungen. Zugangsdaten (Tokens, Client-Secret, Fernsteuerungs-Schlüssel) sind bewusst
 * NICHT enthalten.
 *
 * Version 1 (ein Fahrzeug, alles auf oberster Ebene) wird weiter gelesen und dem gewählten Fahrzeug zugeordnet.
 */
class Backup(private val ctx: Context) {
    private val settings = AppSettings(ctx)

    fun build(): String {
        val vehicles = JSONArray()
        for (id in Garage.knownIds(ctx)) {
            val trips = Stores.trips(ctx, id).rawEntries()
            val energy = Stores.energy(ctx, id).exportJson()
            val costs = Stores.costs(ctx, id).toJson()
            // Platzhalter ohne Daten nicht mitsichern
            if (id == Garage.UNASSIGNED && trips.length() == 0 && energy.length() == 0) continue
            vehicles.put(
                JSONObject()
                    .put("id", id)
                    .put("vin", Garage.vehicle(ctx, id)?.vin ?: JSONObject.NULL)
                    .put("name", Garage.name(ctx, id))
                    .put("trips", trips)
                    .put("costs", costs)
                    .put("energy", energy)
                    .put("settings", VehicleSettings(ctx, id).toJson()),
            )
        }
        return JSONObject()
            .put("format", FORMAT)
            .put("version", 2)
            .put("createdAt", Instant.now().toString())
            .put("places", Stores.places(ctx).toJson())
            .put("settings", settings.toJson())
            .put("vehicles", vehicles)
            .toString()
    }

    fun restore(text: String): RestoreResult {
        val o = runCatching { JSONObject(text.trim().removePrefix("﻿")) }.getOrNull()
        if (o == null || o.optString("format") != FORMAT) throw IllegalArgumentException("Die Datei ist keine Astra-Connect-Sicherung.")
        o.optJSONObject("places")?.let { Stores.places(ctx).restore(it) }
        o.optJSONObject("settings")?.let(settings::restore)

        val entries = o.optJSONArray("vehicles")?.objects()
        if (entries == null) {
            // Version 1: alles gehört zum gewählten Fahrzeug
            return restoreVehicle(Garage.selectedId(ctx), o, o.optJSONObject("settings"))
        }
        var trips = 0
        var snaps = 0
        for (e in entries) {
            val r = restoreVehicle(targetId(e), e, e.optJSONObject("settings"))
            trips += r.trips
            snaps += r.snapshots
        }
        return RestoreResult(trips, snaps, entries.size)
    }

    private fun restoreVehicle(id: String, o: JSONObject, vehicleSettings: JSONObject?): RestoreResult {
        val trips = Stores.trips(ctx, id).restore(o.optJSONArray("trips") ?: JSONArray())
        o.optJSONObject("costs")?.let { Stores.costs(ctx, id).restore(it) }
        val snaps = Stores.energy(ctx, id).restore(o.optJSONArray("energy") ?: JSONArray())
        vehicleSettings?.let { VehicleSettings(ctx, id).restore(it) }
        return RestoreResult(trips, snaps)
    }

    /**
     * Wohin ein gesichertes Fahrzeug gehört: gleiche ID im Konto, sonst gleiche VIN, sonst die gesicherte ID
     * (Opel-IDs bleiben gleich – nach dem nächsten Login passt sie dann wieder).
     */
    private fun targetId(e: JSONObject): String {
        val id = e.optString("id").ifBlank { Garage.UNASSIGNED }
        val known = Garage.vehicles(ctx)
        if (known.any { it.id == id }) return id
        val vin = e.str("vin")
        known.firstOrNull { vin != null && it.vin.equals(vin, ignoreCase = true) }?.let { return it.id }
        return if (id == Garage.UNASSIGNED) Garage.selectedId(ctx) else id
    }

    fun writeTo(uri: Uri) {
        ctx.contentResolver.openOutputStream(uri, "wt")?.use { it.write(build().toByteArray(Charsets.UTF_8)) }
            ?: throw IllegalStateException("Datei konnte nicht geschrieben werden.")
    }

    fun readFrom(uri: Uri): RestoreResult {
        val text = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
            ?: throw IllegalStateException("Datei konnte nicht gelesen werden.")
        return restore(text)
    }

    /**
     * Automatische Sicherung in den gewählten Ordner: eine laufend überschriebene Datei
     * plus eine Monatsdatei (so bleiben ältere Stände erhalten). Liefert false, wenn kein
     * Ordner gewählt ist oder der Zugriff nicht mehr erlaubt ist.
     */
    fun autoBackup(force: Boolean = false): Boolean {
        val tree = settings.backupTreeUri?.let(Uri::parse) ?: return false
        if (!force && System.currentTimeMillis() - settings.lastBackupAt < 20 * 3600_000L) return true
        val dir = DocumentFile.fromTreeUri(ctx, tree)?.takeIf { it.canWrite() } ?: return false
        val data = build().toByteArray(Charsets.UTF_8)
        for (name in listOf("astra_connect_backup.json", "astra_connect_backup_${YearMonth.now()}.json")) {
            val f = dir.findFile(name) ?: dir.createFile("application/json", name) ?: continue
            ctx.contentResolver.openOutputStream(f.uri, "wt")?.use { it.write(data) }
        }
        settings.lastBackupAt = System.currentTimeMillis()
        return true
    }

    companion object {
        const val FORMAT = "astra-connect-backup"
        fun fileName() = "astra_connect_backup_${LocalDate.now()}.json"
    }
}
