package com.astraconnect.app.data

import org.json.JSONArray
import org.json.JSONObject

/** Ein Navigationsziel (WGS84). [name] dient nur der Anzeige – das Auto bekommt nur Koordinaten. */
data class NavTarget(val name: String, val lat: Double, val lon: Double) {
    val pos get() = LatLon(lat, lon)
}

/** Zustand eines an das Auto gesendeten Navigationsziels. */
data class NavState(val label: String, val final: Boolean, val success: Boolean)

/**
 * Remote „Navigation“ der Stellantis-Web-API v4 (B2C):
 *  1. Callback anlegen: `POST /user/callbacks` (einmalig, wird wiederverwendet)
 *  2. Ziel senden: `POST /user/vehicles/{id}/callbacks/{cbid}/remotes` mit `{"navigation":{"positions":[…]}}`
 *  3. Status abfragen: `GET /user/vehicles/{id}/callbacks/{cbid}/remotes/{rid}`
 *
 * Laut Doku ist diese Schnittstelle „nur auf Anfrage“ freigeschaltet und braucht den Scope
 * `remote:navigation:write`. Ob der MyOpel-Zugang das darf, ist nicht dokumentiert – Fehler werden
 * deshalb möglichst verständlich gemeldet.
 */
object Navigation {
    const val CALLBACK_LABEL = "AstraConnect"

    /**
     * Die API verlangt beim Callback ein Ziel (Webhook). Die App braucht keine Rückmeldungen per Webhook,
     * sie fragt den Status selbst ab. `.invalid` ist nach RFC 2606 nie auflösbar – so gehen keine Daten
     * an fremde Server.
     */
    const val CALLBACK_TARGET = "https://astra-connect.invalid/remote"

    /** Höchstens 9 Zwischenziele + Ziel. */
    const val MAX_POSITIONS = 10

    fun callbackBody(): JSONObject = JSONObject()
        .put("label", CALLBACK_LABEL)
        .put("type", JSONArray().put("Remote"))
        .put(
            "callback", JSONObject().put(
                "webhook", JSONObject()
                    .put("name", CALLBACK_LABEL)
                    .put("target", CALLBACK_TARGET)
            )
        )

    /** Sucht den eigenen, laufenden Callback in `GET /user/callbacks`. */
    fun findCallbackId(list: JSONObject?): String? =
        list.obj("_embedded").arr("callbacks").objects()
            .filter { it.optString("label") == CALLBACK_LABEL || it.obj("subscribe")?.optString("label") == CALLBACK_LABEL }
            .filter { it.optString("status", "Running") != "Failed" }
            .firstNotNullOfOrNull { it.optString("id").ifBlank { it.optString("callbackId") }.ifBlank { null } }

    /** Callback-ID aus der Antwort auf `POST /user/callbacks`. */
    fun createdCallbackId(resp: JSONObject): String? =
        resp.optString("callbackId").ifBlank { resp.optString("id") }.ifBlank { null }

    /** Anfrage für das Ziel. GeoJSON (RFC 7946): Koordinaten in der Reihenfolge Länge, Breite. */
    fun remoteBody(targets: List<LatLon>): JSONObject {
        require(targets.isNotEmpty()) { "Kein Ziel angegeben." }
        require(targets.size <= MAX_POSITIONS) { "Höchstens $MAX_POSITIONS Punkte." }
        val positions = JSONArray()
        targets.forEach { p ->
            positions.put(
                JSONObject().put("type", "Point").put("coordinates", JSONArray().put(p.lon).put(p.lat))
            )
        }
        return JSONObject()
            .put("label", "$CALLBACK_LABEL Navigation")
            .put("navigation", JSONObject().put("positions", positions))
    }

    fun remoteActionId(resp: JSONObject): String? = resp.optString("remoteActionId").ifBlank { null }

    /** `GET /user/vehicles/{id}?extension=onboardCapabilities` → unterstützt das Auto die Funktion? null = unbekannt. */
    fun supported(vehicle: JSONObject?): Boolean? {
        val nav = vehicle.obj("_embedded").obj("extension").obj("onboardCapabilities").obj("remote").obj("navigation")
            ?: vehicle.obj("extension").obj("onboardCapabilities").obj("remote").obj("navigation")
            ?: return null
        return if (nav.has("supported")) nav.optBoolean("supported") else null
    }

    /** Wertet den Status einer gesendeten Navigation aus. */
    fun state(action: JSONObject): NavState {
        val status = action.optString("status")
        val cause = action.optString("failureCause").ifBlank { null }
        return when (status) {
            "Success" -> NavState("Ziel ist im Navi angekommen.", final = true, success = true)
            "AlreadyDone" -> NavState("Ziel war bereits gesetzt.", final = true, success = true)
            "SentToVehicle" -> NavState("An das Auto übertragen …", final = false, success = false)
            "CheckingVehicle", "WakingUpVehicle", "Accepted", "" -> NavState("Opel hat das Ziel angenommen …", final = false, success = false)
            "VehicleBatteryChargeTooLow" -> NavState("Fehlgeschlagen: Batterie des Autos zu schwach.", final = true, success = false)
            "TooManyWakeUpsOverMonth" -> NavState("Fehlgeschlagen: zu viele Weckvorgänge in diesem Monat.", final = true, success = false)
            "Failed" -> NavState("Fehlgeschlagen: ${failureLabel(cause)}", final = true, success = false)
            else -> NavState("Status: $status", final = false, success = false)
        }
    }

    fun failureLabel(cause: String?): String = when (cause) {
        null -> "unbekannter Grund"
        "MissingRights" -> "fehlende Berechtigung (Opel erlaubt diesen Befehl für den Zugang nicht)."
        "VehicleConnectionTimeout" -> "Auto nicht erreichbar (kein Netz?)."
        "NotPossibleDueToVehiclePrivacyLevel" -> "Datenschutz-Modus im Auto verhindert das."
        "CanceledByDriver" -> "im Auto abgelehnt."
        "VehicleInUse" -> "Auto wird gerade benutzt."
        "TooManyRequestInShortTime", "TooManyRequestSent" -> "zu viele Anfragen, bitte später erneut."
        "SameActionInProgress" -> "es wird bereits ein Ziel übertragen."
        "WrongCommand" -> "Befehl vom Auto nicht unterstützt."
        "VehicleError", "GeneralError" -> "Fehler im Auto bzw. bei Opel."
        else -> cause
    }
}
