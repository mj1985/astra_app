package com.astraconnect.app.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PowertrainTest {
    private fun status(electric: Double?, fuel: Double?): VehicleStatus {
        val energies = org.json.JSONArray()
        electric?.let { energies.put(JSONObject().put("type", "Electric").put("level", it)) }
        fuel?.let { energies.put(JSONObject().put("type", "Fuel").put("level", it)) }
        return VehicleStatus.parse(JSONObject().put("energies", energies))
    }

    private val astraPhevVin = "VXKF3DGXTP1234567"

    @Test
    fun detectsPowertrainFromReportedEnergies() {
        assertEquals(Powertrain.PHEV, Powertrain.fromStatus(status(80.0, 50.0)))
        assertEquals(Powertrain.BEV, Powertrain.fromStatus(status(80.0, null)))
        assertEquals(Powertrain.ICE, Powertrain.fromStatus(status(null, 50.0)))
        assertNull(Powertrain.fromStatus(status(null, null)))
        assertNull(Powertrain.fromStatus(null))
    }

    @Test
    fun astraPhev_keepsPreviousDefaults() {
        val p = VehicleProfile.resolve(null, null, null, astraPhevVin, status(80.0, 50.0))
        assertEquals(Powertrain.PHEV, p.powertrain)
        assertEquals(ProfileSource.STATUS, p.source)
        assertEquals("Astra L Hybrid", p.model?.name)
        assertEquals(10.2, p.capacityKwh, 1e-9) // 12,4 brutto × 0,82
        assertEquals(42.0, p.tankLiters, 1e-9)
        assertFalse(p.capacityManual)
    }

    @Test
    fun electricModel_usesListCapacity_andHasNoTank() {
        val p = VehicleProfile.resolve(null, null, null, "VXKUHZKX00000000", null)
        assertEquals(Powertrain.BEV, p.powertrain)
        assertEquals(ProfileSource.MODEL, p.source)
        assertEquals(46.0, p.capacityKwh, 1e-9)
        assertEquals(0.0, p.tankLiters, 1e-9)
    }

    @Test
    fun manualValuesWin() {
        val p = VehicleProfile.resolve(Powertrain.ICE, 9.0, 55.0, astraPhevVin, status(80.0, 50.0))
        assertEquals(Powertrain.ICE, p.powertrain)
        assertEquals(ProfileSource.MANUAL, p.source)
        assertEquals(0.0, p.capacityKwh, 1e-9) // Verbrenner: keine Antriebsbatterie
        assertEquals(55.0, p.tankLiters, 1e-9)
        assertTrue(p.tankManual)
    }

    @Test
    fun modelSizesOnlyWhenPowertrainMatches() {
        // Liste sagt Plug-in-Hybrid, selbst gewählt ist Elektroauto → Standard statt Listenwert
        val p = VehicleProfile.resolve(Powertrain.BEV, null, null, astraPhevVin, null)
        assertEquals(VehicleProfile.DEFAULT_BEV_KWH, p.capacityKwh, 1e-9)
    }

    @Test
    fun unknownCar_defaultsToPhev() {
        val p = VehicleProfile.resolve(null, null, null, "XXXXXXXXXX", null)
        assertEquals(Powertrain.PHEV, p.powertrain)
        assertEquals(ProfileSource.DEFAULT, p.source)
        assertNull(p.model)
        assertEquals(VehicleProfile.DEFAULT_PHEV_KWH, p.capacityKwh, 1e-9)
        assertEquals(VehicleProfile.DEFAULT_TANK_L, p.tankLiters, 1e-9)
    }
}
