package com.astraconnect.app.data

import org.json.JSONObject
import java.time.Duration
import java.time.Instant

data class Vehicle(
    val id: String,
    val vin: String,
    val brand: String?,
    val label: String?,
    val motorization: String?,
    val pictureUrl: String?,
) {

    companion object {
        fun parse(o: JSONObject) = Vehicle(
            id = o.str("id").orEmpty(),
            vin = o.str("vin").orEmpty(),
            brand = o.str("brand"),
            label = o.str("label"),
            motorization = o.str("motorization"),
            pictureUrl = o.arr("pictures").strings().firstOrNull(),
        )
    }
}

data class LatLon(val lat: Double, val lon: Double)

data class Opening(val identifier: String, val label: String, val state: String?) {
    /** true = offen, false = geschlossen, null = unbekannt */
    val isOpen: Boolean?
        get() = when {
            state == null -> null
            state.equals("Closed", true) -> false
            state.contains("open", true) -> true
            else -> null
        }

    companion object {
        fun label(id: String): String = when (id.lowercase()) {
            "driver" -> "Fahrertür"
            "passenger" -> "Beifahrertür"
            "rearleft" -> "Tür hinten links"
            "rearright" -> "Tür hinten rechts"
            "trunk", "boot" -> "Kofferraum / Heckklappe"
            "rearwindow" -> "Heckscheibe"
            "hood", "bonnet" -> "Motorhaube"
            "fuelflap", "fueldoor" -> "Tankdeckel"
            "chargingflap", "chargingdoor" -> "Ladeklappe"
            "driverwindow", "frontleftwindow" -> "Fenster Fahrer"
            "passengerwindow", "frontrightwindow" -> "Fenster Beifahrer"
            "rearleftwindow" -> "Fenster hinten links"
            "rearrightwindow" -> "Fenster hinten rechts"
            else -> id
        }
    }
}

data class ChargingInfo(
    val plugged: Boolean?,
    val status: String?,
    val mode: String?,
    val remaining: Duration?,
    val rateKmh: Double?,
    val nextDelayedTime: String?,
    /** „Partial“ = im Auto eingestelltes Ladelimit aktiv (nur neuere Modelle), sonst z. B. „Full“. */
    val type: String? = null,
) {
    val nativeLimitActive get() = type.equals("Partial", true)
    val statusLabel: String
        get() = when (status?.lowercase()) {
            null -> "–"
            "inprogress" -> "Lädt"
            "disconnected" -> "Nicht verbunden"
            "finished" -> "Ladung abgeschlossen"
            "stopped" -> "Gestoppt"
            "failure" -> "Fehler"
            else -> status
        }
    val isCharging get() = status.equals("InProgress", true)
}

data class VehicleStatus(
    val updatedAt: Instant?,
    val mileageKm: Double?,
    val batteryPercent: Double?,
    val electricRangeKm: Double?,
    val fuelPercent: Double?,
    val fuelRangeKm: Double?,
    val charging: ChargingInfo?,
    val battery12V: Double?,
    val battery12VCurrent: Double?,
    val battery12VUpdatedAt: Instant?,
    val maintenanceDays: Double?,
    val maintenanceKm: Double?,
    val lockStates: List<String>,
    val doors: List<Opening>,
    val windows: List<Opening>,
    val windowsReported: Boolean,
    val position: LatLon?,
    val positionAt: Instant?,
    val outsideTempC: Double?,
    val ignition: String?,
    val moving: Boolean?,
    val preconditioning: String?,
    val alerts: List<String>,
) {
    val totalRangeKm: Double?
        get() = if (electricRangeKm == null && fuelRangeKm == null) null
        else (electricRangeKm ?: 0.0) + (fuelRangeKm ?: 0.0)

    /** true = verriegelt, false = entriegelt, null = unbekannt */
    val isLocked: Boolean?
        get() = when {
            lockStates.isEmpty() -> null
            lockStates.any { it.contains("unlocked", true) } -> false
            lockStates.any { it.contains("locked", true) } -> true
            else -> null
        }

    val lockLabel: String
        get() = when {
            lockStates.any { it.equals("SuperLocked", true) } -> "Doppelt verriegelt"
            isLocked == true -> "Verriegelt"
            lockStates.any { it.equals("DriverDoorUnlocked", true) } -> "Fahrertür entriegelt"
            isLocked == false -> "Entriegelt"
            else -> "Unbekannt"
        }

    val anyDoorOpen get() = doors.any { it.isOpen == true }
    val anyWindowOpen get() = windows.any { it.isOpen == true }

    val ignitionLabel: String
        get() = when (ignition?.lowercase()) {
            null -> "–"
            "stop" -> "Aus"
            "startup" -> "Motor an"
            "start" -> "Zündung an"
            "free" -> "Frei"
            else -> ignition
        }

    companion object {
        fun parse(json: JSONObject, maintenanceJson: JSONObject? = null): VehicleStatus {
            val energies = json.arr("energies").objects() + json.arr("energy").objects()
            val el = energies.firstWith("type", "Electric")
            val fuel = energies.firstWith("type", "Fuel")
            val ch = el.pathObj("extension", "electric", "charging") ?: el.obj("charging")

            val charging = ch?.let {
                ChargingInfo(
                    plugged = it.bool("plugged"),
                    status = it.str("status"),
                    mode = it.str("chargingMode"),
                    remaining = it.str("remainingTime")?.let { s -> runCatching { Duration.parse(s) }.getOrNull() },
                    rateKmh = it.num("chargingRate"),
                    nextDelayedTime = it.str("nextDelayedTime"),
                    type = it.str("type"),
                )
            }

            val battery = json.obj("battery")
            val maint = listOfNotNull(maintenanceJson, json.obj("maintenance"), json.obj("service"))

            val doorsState = json.obj("doorsState")
            val lockStates = doorsState.arr("lockedStates").strings().ifEmpty {
                listOfNotNull(doorsState.str("lockedState"), doorsState.str("lockedStates"))
            }

            fun openingsOf(o: JSONObject?) = (o.arr("opening").objects() + o.arr("openings").objects())
                .mapNotNull { op ->
                    val id = op.str("identifier") ?: op.str("id") ?: return@mapNotNull null
                    Opening(id, Opening.label(id), op.str("state"))
                }

            val isRoof: (Opening) -> Boolean = {
                it.identifier.contains("roof", true) || it.identifier.contains("sunroof", true)
            }
            val isWindow: (Opening) -> Boolean = {
                it.identifier.contains("window", true) && !it.identifier.equals("RearWindow", true)
            }
            val doorOpenings = openingsOf(doorsState).filterNot(isRoof)
            val windowSources = listOfNotNull(
                json.obj("windowsState"), json.obj("windows"), doorsState.obj("windows")
            )
            val explicitWindows = windowSources.flatMap { openingsOf(it) } +
                json.arr("windows").objects().mapNotNull { w ->
                    val id = w.str("identifier") ?: return@mapNotNull null
                    Opening(id, Opening.label(id), w.str("state"))
                }
            val windows = (explicitWindows + doorOpenings.filter(isWindow)).filterNot(isRoof)
                .distinctBy { it.identifier.lowercase() }
            val doors = doorOpenings.filterNot(isWindow)

            val coords = json.pathObj("lastPosition", "geometry")?.optJSONArray("coordinates")
            val position = if (coords != null && coords.length() >= 2)
                LatLon(lat = coords.optDouble(1), lon = coords.optDouble(0)) else null

            val alerts = json.arr("alerts").objects().map { a ->
                listOfNotNull(a.str("type"), a.str("name"), a.str("label")).firstOrNull() ?: a.toString()
            }

            return VehicleStatus(
                updatedAt = parseInstant(json.str("lastUpdate") ?: json.str("updatedAt") ?: json.str("createdAt")),
                mileageKm = json.pathNum("odometer", "mileage") ?: json.pathNum("timed.odometer", "mileage"),
                batteryPercent = el.num("level"),
                electricRangeKm = el.num("autonomy"),
                fuelPercent = fuel.num("level"),
                fuelRangeKm = fuel.num("autonomy"),
                charging = charging,
                battery12V = battery.num("voltage"),
                battery12VCurrent = battery.num("current"),
                battery12VUpdatedAt = parseInstant(battery.str("createdAt") ?: battery.str("updatedAt")),
                maintenanceDays = maint.firstNotNullOfOrNull { it.num("daysBeforeMaintenance") },
                maintenanceKm = maint.firstNotNullOfOrNull { it.num("mileageBeforeMaintenance") },
                lockStates = lockStates,
                doors = doors,
                windows = windows,
                windowsReported = windows.isNotEmpty(),
                position = position,
                // Laut API-Spezifikation „createdAt“; „updatedAt“ nur zur Sicherheit
                positionAt = parseInstant(
                    json.pathStr("lastPosition", "properties", "createdAt")
                        ?: json.pathStr("lastPosition", "properties", "updatedAt")
                ),
                outsideTempC = json.pathNum("environment", "air", "temp"),
                ignition = json.pathStr("ignition", "type"),
                moving = json.path("kinetic", "moving") as? Boolean,
                preconditioning = firstStr(
                    json,
                    "preconditioning.airConditioning.status",
                    "preconditionning.airConditioning.status"
                ),
                alerts = alerts,
            )
        }
    }
}
