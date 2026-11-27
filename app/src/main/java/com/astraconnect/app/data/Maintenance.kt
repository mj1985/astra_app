package com.astraconnect.app.data

import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * Effektive Wartungsinfo einer Fahrt bzw. eines Fahrzeugs.
 *
 * Die Connected-Car-API liefert bei manchen Modellen (u. a. Mild-Hybrid und Elektro) einen falschen
 * Wartungswert. Deshalb kann je Fahrzeug ein eigener Termin hinterlegt werden ([VehicleSettings]).
 * Ist einer gesetzt, hat er Vorrang – der (falsche) API-Wert wird dann gar nicht mehr herangezogen,
 * damit sich kein korrekter mit einem falschen Wert mischt. Den genaueren Wert zeigt die MyOpel-App;
 * ihn abzutippen genügt, den Kilometer-Teil zählt die App danach selbst mit dem Kilometerstand herunter.
 */
data class MaintenanceInfo(
    /** Verbleibende Tage (negativ = überfällig); null = unbekannt. */
    val daysLeft: Double?,
    /** Verbleibende Kilometer (negativ = überfällig); null = unbekannt. */
    val kmLeft: Double?,
    /** Fälligkeitsdatum, sofern bekannt. */
    val dueDate: LocalDate?,
    /** Kilometerstand bei Fälligkeit, sofern bekannt. */
    val dueMileage: Double?,
    /** true = selbst eingetragener Wert (statt API). */
    val manual: Boolean,
) {
    val hasData: Boolean get() = daysLeft != null || kmLeft != null || dueDate != null || dueMileage != null

    /** Wartung fällig: ≤ 30 Tage oder ≤ 1000 km (auch überfällig). */
    val due: Boolean
        get() = (daysLeft != null && daysLeft <= 30) || (kmLeft != null && kmLeft <= 1000)

    companion object {
        /**
         * Baut die Anzeige aus API-Werten und dem selbst eingetragenen Termin.
         * @param apiDays/apiKm verbleibende Tage/km laut API ([VehicleStatus.maintenanceDays]/[VehicleStatus.maintenanceKm])
         * @param currentMileage aktueller Kilometerstand (für die Umrechnung des eingetragenen Ziel-Kilometerstands)
         * @param manualDueDate/manualDueMileage selbst eingetragener Termin (absolut); je null = nicht gesetzt
         */
        fun resolve(
            apiDays: Double?,
            apiKm: Double?,
            currentMileage: Double?,
            manualDueDate: LocalDate?,
            manualDueMileage: Double?,
            today: LocalDate = LocalDate.now(),
        ): MaintenanceInfo {
            if (manualDueDate != null || manualDueMileage != null) {
                return MaintenanceInfo(
                    daysLeft = manualDueDate?.let { ChronoUnit.DAYS.between(today, it).toDouble() },
                    kmLeft = manualDueMileage?.let { due -> currentMileage?.let { due - it } },
                    dueDate = manualDueDate,
                    dueMileage = manualDueMileage,
                    manual = true,
                )
            }
            return MaintenanceInfo(
                daysLeft = apiDays,
                kmLeft = apiKm,
                dueDate = apiDays?.let { today.plusDays(it.toLong()) },
                dueMileage = apiKm?.let { km -> currentMileage?.let { it + km } },
                manual = false,
            )
        }
    }
}
