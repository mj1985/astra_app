package com.astraconnect.shared

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WearContractTest {

    private val full = WearStatus(
        vehicleId = "abc", vehicleName = "Astra", hasBattery = true, hasClimate = true,
        batteryPercent = 78, fuelPercent = 40, rangeText = "54 km el. · 610 km", chargeText = "Lädt · noch 1 h 20 min",
        charging = true, locked = true, climateOn = false, updatedAt = 1_700_000_000_000, sending = setOf("lock"),
    )

    @Test
    fun statusRoundTrip() {
        assertEquals(full, WearStatus.fromJson(full.toJson()))
    }

    @Test
    fun statusWithoutValues() {
        val empty = full.copy(batteryPercent = null, fuelPercent = null, locked = null, updatedAt = null, sending = emptySet())
        assertEquals(empty, WearStatus.fromJson(empty.toJson()))
    }

    @Test
    fun mainPercentFallsBackToFuel() {
        assertEquals(78, full.mainPercent)
        assertEquals(40, full.copy(hasBattery = false).mainPercent)
    }

    @Test
    fun labelOnlyWithSeveralVehicles() {
        val single = full.copy(vehicleName = "Opel Astra L Hybrid", vehicleCount = 1)
        assertNull(single.label)
        assertEquals("Astra L Hybrid", single.copy(vehicleCount = 2).label)
        assertEquals(2, WearStatus.fromJson(single.copy(vehicleCount = 2).toJson())?.vehicleCount)
    }

    @Test
    fun longNamesAreShortened() {
        assertEquals("Grandland Ele…", WearStatus.shortName("Opel Grandland Electric"))
        assertEquals("Mokka-e", WearStatus.shortName("Mokka-e"))
        assertEquals("Astra Sports…", WearStatus.shortName("Opel Astra Sports Tourer Hybrid"))
    }

    @Test
    fun invalidStatusIsNull() {
        assertNull(WearStatus.fromJson("kein json"))
    }

    @Test
    fun commandRoundTrip() {
        assertEquals(WearContract.LOCK to "abc", WearContract.parseCommand(WearContract.command(WearContract.LOCK, "abc")))
    }

    @Test
    fun unknownCommandIsRejected() {
        // Nur die freigegebenen Befehle – z. B. kein Hupen oder Laden von der Uhr
        assertNull(WearContract.parseCommand("""{"cmd":"HORN","vehicle":"abc"}""".toByteArray()))
        assertNull(WearContract.parseCommand("Müll".toByteArray()))
    }
}
