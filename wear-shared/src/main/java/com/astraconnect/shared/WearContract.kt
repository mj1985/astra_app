package com.astraconnect.shared

import org.json.JSONArray
import org.json.JSONObject

/**
 * Datenaustausch zwischen Handy- und Uhr-App über die Wearable Data Layer API.
 * Das klappt nur, wenn beide Apps dieselbe Paket-ID haben und mit demselben Schlüssel signiert sind.
 */
object WearContract {
    /** Datenelement mit dem Stand des gewählten Fahrzeugs (Handy → Uhr), Inhalt unter [KEY_JSON]. */
    const val PATH_STATUS = "/blitzlink/status"

    /** Nachricht mit einem Befehl (Uhr → Handy), Inhalt: JSON aus [command]. */
    const val PATH_COMMAND = "/blitzlink/command"

    /** Nachricht „bitte neuen Stand holen“ (Uhr → Handy), ohne Inhalt. */
    const val PATH_REFRESH = "/blitzlink/refresh"

    const val KEY_JSON = "json"

    /** Capability der Handy-App (res/values/wear.xml), damit die Uhr das richtige Gerät findet. */
    const val CAPABILITY_PHONE = "blitzlink_phone"

    /** Befehle, die die Uhr senden darf (Namen von WidgetCommand der Handy-App). */
    const val CLIMATE_ON = "CLIMATE_ON"
    const val CLIMATE_OFF = "CLIMATE_OFF"
    const val LOCK = "LOCK"
    val COMMANDS = setOf(CLIMATE_ON, CLIMATE_OFF, LOCK)

    /** Tasten-Schlüssel laufender Befehle in [WearStatus.sending] – wie in der Handy-App (WidgetCommand.key). */
    const val KEY_CLIMATE = "climate"
    const val KEY_LOCK = "lock"

    fun command(cmd: String, vehicleId: String): ByteArray =
        JSONObject().put("cmd", cmd).put("vehicle", vehicleId).toString().toByteArray(Charsets.UTF_8)

    /** (Befehl, Fahrzeug) aus einer Befehls-Nachricht; null bei unbekanntem Befehl. */
    fun parseCommand(data: ByteArray): Pair<String, String?>? {
        val o = runCatching { JSONObject(String(data, Charsets.UTF_8)) }.getOrNull() ?: return null
        val cmd = o.optString("cmd").takeIf { it in COMMANDS } ?: return null
        return cmd to o.optString("vehicle").ifBlank { null }
    }
}

/** Stand eines Fahrzeugs für Tile und Complication – Texte fertig aufbereitet vom Handy. */
data class WearStatus(
    val vehicleId: String,
    val vehicleName: String,
    val hasBattery: Boolean,
    /** Vorklimatisierung aus der Ferne möglich (Auto mit Antriebsbatterie). */
    val hasClimate: Boolean,
    val batteryPercent: Int?,
    val fuelPercent: Int?,
    /** z. B. „54 km“ oder „54 km el. · 610 km“. */
    val rangeText: String,
    /** Kurz für den runden Rand, z. B. „Lädt bis 14:35“; leer ohne Antriebsbatterie. */
    val chargeText: String,
    val charging: Boolean,
    /** true = verriegelt, false = entriegelt, null = unbekannt. */
    val locked: Boolean?,
    val climateOn: Boolean,
    /** Meldezeitpunkt des Autos (Epoch-Millisekunden). */
    val updatedAt: Long?,
    /** Tasten mit gerade laufendem Befehl ([WearContract.KEY_CLIMATE], [WearContract.KEY_LOCK]). */
    val sending: Set<String>,
    /** Fahrzeuge im Konto – bei nur einem zeigt die Uhr keinen Namen. */
    val vehicleCount: Int = 1,
) {
    /** Hauptwert für Ring und Complication: Akku, beim Verbrenner der Tank. */
    val mainPercent: Int? get() = if (hasBattery) batteryPercent else fuelPercent

    /** Name für den oberen Rand: nur bei mehreren Fahrzeugen, gekürzt auf [MAX_LABEL] Zeichen. */
    val label: String? get() = if (vehicleCount > 1) shortName(vehicleName) else null

    fun toJson(): String = JSONObject()
        .put("vehicleId", vehicleId)
        .put("vehicleName", vehicleName)
        .put("hasBattery", hasBattery)
        .put("hasClimate", hasClimate)
        .put("battery", batteryPercent ?: JSONObject.NULL)
        .put("fuel", fuelPercent ?: JSONObject.NULL)
        .put("range", rangeText)
        .put("charge", chargeText)
        .put("charging", charging)
        .put("locked", locked ?: JSONObject.NULL)
        .put("climateOn", climateOn)
        .put("updatedAt", updatedAt ?: JSONObject.NULL)
        .put("sending", JSONArray(sending.sorted()))
        .put("vehicles", vehicleCount)
        .toString()

    companion object {
        /** Oben auf einer runden 192-dp-Uhr (Pixel Watch 1) passen etwa so viele Zeichen. */
        const val MAX_LABEL = 14

        /** „Opel Astra L Hybrid“ → „Astra L Hybrid“; längere Namen mit „…“ gekürzt. */
        fun shortName(name: String): String {
            val n = name.trim().let { if (it.startsWith("Opel ", ignoreCase = true)) it.substring(5).trim() else it }
            return if (n.length <= MAX_LABEL) n else n.take(MAX_LABEL - 1).trimEnd() + "…"
        }

        fun fromJson(text: String): WearStatus? = runCatching {
            val o = JSONObject(text)
            fun int(k: String) = if (o.isNull(k)) null else o.getInt(k)
            WearStatus(
                vehicleId = o.getString("vehicleId"),
                vehicleName = o.optString("vehicleName"),
                hasBattery = o.optBoolean("hasBattery", true),
                hasClimate = o.optBoolean("hasClimate"),
                batteryPercent = int("battery"),
                fuelPercent = int("fuel"),
                rangeText = o.optString("range", "–"),
                chargeText = o.optString("charge"),
                charging = o.optBoolean("charging"),
                locked = if (o.isNull("locked")) null else o.getBoolean("locked"),
                climateOn = o.optBoolean("climateOn"),
                updatedAt = if (o.isNull("updatedAt")) null else o.getLong("updatedAt"),
                sending = o.optJSONArray("sending")?.let { a -> (0 until a.length()).map { a.getString(it) }.toSet() } ?: emptySet(),
                vehicleCount = o.optInt("vehicles", 1),
            )
        }.getOrNull()
    }
}
