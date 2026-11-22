package com.astraconnect.app.data

import android.content.Context
import org.json.JSONObject
import kotlin.math.roundToInt

/** Antriebsart – bestimmt, welche Bereiche (Laden, Tanken, Klima …) die App zeigt. */
enum class Powertrain(val label: String, val hasBattery: Boolean, val hasFuel: Boolean) {
    PHEV("Plug-in-Hybrid", hasBattery = true, hasFuel = true),
    BEV("Elektroauto", hasBattery = true, hasFuel = false),
    ICE("Verbrenner / Mild-Hybrid", hasBattery = false, hasFuel = true);

    /** Vorklimatisierung und Laden aus der Ferne gibt es nur mit Antriebsbatterie. */
    val hasRemoteClimate get() = hasBattery

    companion object {
        fun of(name: String?) = entries.firstOrNull { it.name == name }

        /** Aus den gemeldeten Energiearten (Akku- bzw. Tankstand vorhanden?). null = keine Daten. */
        fun fromStatus(s: VehicleStatus?): Powertrain? = when {
            s == null -> null
            s.batteryPercent != null && s.fuelPercent != null -> PHEV
            s.batteryPercent != null -> BEV
            s.fuelPercent != null -> ICE
            else -> null
        }
    }
}

/**
 * Fahrzeugmodell aus der Modellliste. [batteryKwh] ist der Listenwert – bei Plug-in-Hybriden brutto,
 * bei Elektroautos etwa nutzbar.
 */
data class CarModel(val vinPrefix: String, val name: String, val batteryKwh: Double, val fuelLiters: Double) {
    val powertrain: Powertrain
        get() = when {
            batteryKwh > 0 && fuelLiters > 0 -> Powertrain.PHEV
            batteryKwh > 0 -> Powertrain.BEV
            else -> Powertrain.ICE
        }

    /** Nutzbare Kapazität: bei Plug-in-Hybriden ca. 82 % von brutto (Astra: 12,4 → 10,2 kWh). */
    val usableKwh: Double?
        get() = when (powertrain) {
            Powertrain.PHEV -> (batteryKwh * PHEV_USABLE * 10).roundToInt() / 10.0
            Powertrain.BEV -> batteryKwh
            Powertrain.ICE -> null
        }

    val tankLiters: Double? get() = fuelLiters.takeIf { it > 0 }

    private companion object {
        const val PHEV_USABLE = 0.82
    }
}

/**
 * Opel-Modelle nach VIN-Anfang, übernommen aus `car_models.yml` von psa_car_controller
 * (github.com/flobz/psa_car_controller, Fork martinwuerfele). Der erste passende Eintrag gilt.
 * Korrektur: „Astra L Hybrid“ VXKF3DGXTN dort mit 11,3 kWh – wie alle Astra-L-Hybride 12,4 kWh brutto.
 */
object CarModels {
    val OPEL: List<CarModel> = listOf(
        // Elektro
        CarModel("VXEV1ZKXZM", "Vivaro-e L3", 68.0, 0.0),
        CarModel("VXEV1ZKXZN", "Vivaro-e L1", 68.0, 0.0),
        CarModel("VXKUHZKX", "Corsa-e", 46.0, 0.0),
        CarModel("VXKUHZKWZP", "Corsa Electric", 48.1, 0.0),
        CarModel("VXKUHZKW4S", "Corsa Electric GS Long Range", 54.0, 0.0),
        CarModel("VXKUHZKW1T", "Corsa Electric Long Range", 50.8, 0.0),
        CarModel("VXKUKZKX", "Mokka-e", 46.0, 0.0),
        CarModel("VXEVZZKX", "Zafira-e", 68.0, 0.0),
        CarModel("VXKKCZKZ3T", "Grandland Electric", 73.0, 0.0),
        CarModel("VXKKCZKZ6T", "Grandland Electric AWD", 73.0, 0.0),
        CarModel("W0VEAZKXZM", "Combo-e Cargo", 50.0, 0.0),
        CarModel("W0VEAZKXZP", "Combo-e Cargo XL", 50.0, 0.0),
        CarModel("W0VEZZKXZN", "Combo-e Life", 50.0, 0.0),
        CarModel("VXKFNZKWZP", "Astra L Electric Sports Tourer", 51.0, 0.0),
        CarModel("VXKFMZKWZP", "Astra L Electric", 51.0, 0.0),
        CarModel("VXKFNZKWXR", "Astra L Electric Sports Tourer", 50.8, 0.0),
        CarModel("VXKFNZKW0R", "Astra L Electric Sports Tourer", 50.5, 0.0),
        CarModel("VXKFNZKW3S", "Astra L Electric Sports Tourer", 50.8, 0.0),
        CarModel("VXKFNZKW9S", "Astra L Electric", 51.0, 0.0),
        // Plug-in-Hybrid
        CarModel("W0VZ4DGZ", "Grandland X Hybrid", 13.2, 43.0),
        CarModel("W0VZ45GB", "Grandland X Hybrid4", 13.2, 43.0),
        CarModel("VXKF3DGXTN", "Astra L Hybrid", 12.4, 42.0),
        CarModel("VXKF3DGXTP", "Astra L Hybrid", 12.4, 42.0),
        CarModel("VXKF4DGXTP", "Astra L Sports Tourer Hybrid", 12.4, 42.0),
        CarModel("VXKF3DGYTP", "Astra L GSe Hybrid", 12.4, 42.0),
        CarModel("VXKF4DGYTN", "Astra L Sports Tourer GSe Hybrid", 12.4, 42.0),
        // Verbrenner
        CarModel("VXKUPHNEKM", "Corsa F", 0.0, 44.0),
        CarModel("VXKUPHNEKR", "Corsa F (2024)", 0.0, 44.0),
        CarModel("VXKUPHNSSP", "Corsa F", 0.0, 44.0),
        CarModel("VXKFPHNS2R", "Astra L", 0.0, 52.0),
        CarModel("VXKFPHNPLN", "Astra L", 0.0, 52.0),
    )

    fun find(vin: String?): CarModel? {
        if (vin.isNullOrBlank()) return null
        return OPEL.firstOrNull { vin.startsWith(it.vinPrefix, ignoreCase = true) }
    }
}

/** Woher die Antriebsart stammt. */
enum class ProfileSource(val label: String) {
    MANUAL("selbst gewählt"),
    STATUS("aus den Fahrzeugdaten erkannt"),
    MODEL("aus der Modellliste (VIN)"),
    DEFAULT("Standard"),
}

/** Antriebsart sowie Akku- und Tankgröße des gewählten Fahrzeugs. */
data class VehicleProfile(
    val powertrain: Powertrain,
    val source: ProfileSource,
    val model: CarModel?,
    /** Nutzbare Akkukapazität in kWh (0 ohne Antriebsbatterie). */
    val capacityKwh: Double,
    val capacityManual: Boolean,
    /** Tankinhalt in Litern (0 ohne Tank). */
    val tankLiters: Double,
    val tankManual: Boolean,
) {
    companion object {
        const val DEFAULT_PHEV_KWH = 10.2
        const val DEFAULT_BEV_KWH = 50.0
        const val DEFAULT_TANK_L = 40.0

        /**
         * Reihenfolge Antrieb: eigene Wahl → Fahrzeugdaten → Modellliste → Plug-in-Hybrid.
         * Akku/Tank: eigener Wert → Modellliste (wenn deren Antrieb passt) → Standard für den Antrieb.
         */
        fun resolve(
            manualPowertrain: Powertrain?,
            manualCapacity: Double?,
            manualTank: Double?,
            vin: String?,
            status: VehicleStatus?,
        ): VehicleProfile {
            val model = CarModels.find(vin)
            val detected = Powertrain.fromStatus(status)
            val (pt, source) = when {
                manualPowertrain != null -> manualPowertrain to ProfileSource.MANUAL
                detected != null -> detected to ProfileSource.STATUS
                model != null -> model.powertrain to ProfileSource.MODEL
                else -> Powertrain.PHEV to ProfileSource.DEFAULT
            }
            val fit = model?.takeIf { it.powertrain == pt }
            val capacity = when {
                !pt.hasBattery -> 0.0
                manualCapacity != null -> manualCapacity
                else -> fit?.usableKwh ?: if (pt == Powertrain.BEV) DEFAULT_BEV_KWH else DEFAULT_PHEV_KWH
            }
            val tank = when {
                !pt.hasFuel -> 0.0
                manualTank != null -> manualTank
                else -> fit?.tankLiters ?: DEFAULT_TANK_L
            }
            return VehicleProfile(
                pt, source, model,
                capacity, pt.hasBattery && manualCapacity != null,
                tank, pt.hasFuel && manualTank != null,
            )
        }

        /** Für Widgets und Hintergrund-Job: aus Einstellungen und zuletzt abgerufenen Daten. */
        fun load(ctx: Context): VehicleProfile {
            val settings = AppSettings(ctx)
            val prefs = settings.prefs
            val status = prefs.getString("cache_status", null)
                ?.let { runCatching { VehicleStatus.parse(JSONObject(it)) }.getOrNull() }
            return resolve(settings.powertrainManual, settings.capacityKwhManual, settings.tankLitersManual, cachedVin(prefs), status)
        }

        private fun cachedVin(prefs: android.content.SharedPreferences): String? {
            val id = prefs.getString("vehicle_id", null)
            val list = prefs.getString("cache_vehicles", null)
                ?.let { runCatching { JSONObject(it) }.getOrNull() }
                .obj("_embedded").arr("vehicles").objects()
            return (list.firstOrNull { it.optString("id") == id } ?: list.firstOrNull())?.optString("vin")
        }
    }
}
