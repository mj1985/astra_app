package com.astraconnect.app.data

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.Instant
import java.time.YearMonth

data class RestoreResult(val trips: Int, val snapshots: Int) {
    val summary get() = "$trips Fahrt(en) übernommen/ergänzt, $snapshots Messpunkt(e) ergänzt. Orte, Regeln und Einstellungen eingespielt."
}

/**
 * Datensicherung: Fahrtenbuch (inkl. Kategorien, Notizen, Importe), Orte & Regeln,
 * Ladestand-Messpunkte und Preise/Kapazitäten in einer JSON-Datei.
 * Zugangsdaten (Tokens, Client-Secret, Fernsteuerungs-Schlüssel) sind bewusst NICHT enthalten.
 */
class Backup(private val ctx: Context) {
    private val settings = AppSettings(ctx)

    fun build(): String = JSONObject()
        .put("format", FORMAT)
        .put("version", 1)
        .put("createdAt", Instant.now().toString())
        .put("trips", Stores.trips(ctx).rawEntries())
        .put("places", Stores.places(ctx).toJson())
        .put("energy", Stores.energy(ctx).exportJson())
        .put("settings", settings.toJson())
        .toString()

    fun restore(text: String): RestoreResult {
        val o = runCatching { JSONObject(text.trim().removePrefix("﻿")) }.getOrNull()
        if (o == null || o.optString("format") != FORMAT) throw IllegalArgumentException("Die Datei ist keine Astra-Connect-Sicherung.")
        val trips = Stores.trips(ctx).restore(o.optJSONArray("trips") ?: JSONArray())
        o.optJSONObject("places")?.let { Stores.places(ctx).restore(it) }
        val snaps = Stores.energy(ctx).restore(o.optJSONArray("energy") ?: JSONArray())
        o.optJSONObject("settings")?.let(settings::restore)
        return RestoreResult(trips, snaps)
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
