package com.astraconnect.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class MaintenanceInfoTest {

    private val today = LocalDate.of(2026, 11, 27)

    @Test
    fun apiValuesWhenNoOverride() {
        val m = MaintenanceInfo.resolve(apiDays = 120.0, apiKm = 5000.0, currentMileage = 40000.0, null, null, today)
        assertFalse(m.manual)
        assertEquals(120.0, m.daysLeft!!, 0.0)
        assertEquals(5000.0, m.kmLeft!!, 0.0)
        assertEquals(LocalDate.of(2027, 3, 27), m.dueDate)
        assertEquals(45000.0, m.dueMileage!!, 0.0)
    }

    @Test
    fun overrideTakesPrecedenceOverWrongApiValue() {
        // API meldet (falsch) nur 10 Tage; eigener Termin in der Zukunft gewinnt
        val m = MaintenanceInfo.resolve(10.0, 200.0, 40000.0, LocalDate.of(2027, 1, 27), 46000.0, today)
        assertTrue(m.manual)
        assertEquals(61.0, m.daysLeft!!, 0.0)
        assertEquals(6000.0, m.kmLeft!!, 0.0)
        assertEquals(46000.0, m.dueMileage!!, 0.0)
    }

    @Test
    fun overrideCountsDownWithOdometer() {
        val due = 46000.0
        assertEquals(6000.0, MaintenanceInfo.resolve(null, null, 40000.0, null, due, today).kmLeft!!, 0.0)
        // Nach 1000 km gefahren bleibt weniger übrig
        assertEquals(5000.0, MaintenanceInfo.resolve(null, null, 41000.0, null, due, today).kmLeft!!, 0.0)
    }

    @Test
    fun onlyDateOverride_ignoresApiKm() {
        // Eigener Termin gesetzt (nur Datum) → API-km wird nicht mehr gemischt
        val m = MaintenanceInfo.resolve(10.0, 300.0, 40000.0, LocalDate.of(2027, 5, 1), null, today)
        assertTrue(m.manual)
        assertNull(m.kmLeft)
        assertEquals(LocalDate.of(2027, 5, 1), m.dueDate)
    }

    @Test
    fun overdueIsNegativeAndDue() {
        val m = MaintenanceInfo.resolve(null, null, 50000.0, today.minusDays(5), 49000.0, today)
        assertEquals(-5.0, m.daysLeft!!, 0.0)
        assertEquals(-1000.0, m.kmLeft!!, 0.0)
        assertTrue(m.due)
    }

    @Test
    fun dueThresholds() {
        assertTrue(MaintenanceInfo.resolve(30.0, null, null, null, null, today).due)
        assertFalse(MaintenanceInfo.resolve(31.0, 2000.0, null, null, null, today).due)
        assertTrue(MaintenanceInfo.resolve(null, 1000.0, null, null, null, today).due)
    }

    @Test
    fun noDataAtAll() {
        val m = MaintenanceInfo.resolve(null, null, null, null, null, today)
        assertFalse(m.hasData)
        assertFalse(m.manual)
        assertFalse(m.due)
    }

    @Test
    fun manualKmWithoutOdometer_keepsDueMileageButNoRemaining() {
        val m = MaintenanceInfo.resolve(null, null, null, null, 46000.0, today)
        assertTrue(m.manual)
        assertNull(m.kmLeft)
        assertEquals(46000.0, m.dueMileage!!, 0.0)
        assertTrue(m.hasData)
    }
}
