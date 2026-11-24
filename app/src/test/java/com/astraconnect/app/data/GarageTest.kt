package com.astraconnect.app.data

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class GarageTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun movesLegacyFilesIntoVehicleFolder() {
        val app = tmp.newFolder("files")
        File(app, "fahrtenbuch.json").writeText("[1,2,3]")
        File(app, "energy_log.json").writeText("[]")
        File(app, "orte.json").writeText("{}") // gemeinsam – bleibt liegen
        val target = File(app, "fahrzeuge/abc")

        Garage.moveFiles(app, target, Garage.VEHICLE_FILES)

        assertEquals("[1,2,3]", File(target, "fahrtenbuch.json").readText())
        assertEquals("[]", File(target, "energy_log.json").readText())
        assertFalse(File(app, "fahrtenbuch.json").exists())
        assertTrue(File(app, "orte.json").exists())
        assertFalse(File(target, "kosten.json").exists()) // gab es nicht → nichts angelegt
    }

    @Test
    fun neverOverwritesExistingVehicleData() {
        val app = tmp.newFolder("files")
        val target = File(app, "fahrzeuge/abc").apply { mkdirs() }
        File(app, "fahrtenbuch.json").writeText("alt")
        File(target, "fahrtenbuch.json").writeText("neu")

        Garage.moveFiles(app, target, Garage.VEHICLE_FILES)

        assertEquals("neu", File(target, "fahrtenbuch.json").readText())
        assertEquals("alt", File(app, "fahrtenbuch.json").readText()) // Quelle bleibt erhalten
    }

    @Test
    fun movesVehicleKeysAndKeepsSharedOnes() {
        val app = FakePrefs(
            mutableMapOf(
                "cache_status" to "{\"a\":1}", "capacity_kwh" to 12.5f, "charge_limit_on" to true,
                "charge_limit_pct" to 80, "charge_limit_postponed_at" to 123L,
                "price_fuel" to 1.8f, "vehicle_id" to "abc", "bg_enabled" to true,
            ),
        )
        val car = FakePrefs()

        Garage.moveKeys(app, car, Garage.VEHICLE_KEYS)

        assertEquals("{\"a\":1}", car.getString("cache_status", null))
        assertEquals(12.5f, car.getFloat("capacity_kwh", 0f))
        assertTrue(car.getBoolean("charge_limit_on", false))
        assertEquals(80, car.getInt("charge_limit_pct", 0))
        assertEquals(123L, car.getLong("charge_limit_postponed_at", 0L))
        assertFalse(app.contains("cache_status"))
        assertFalse(app.contains("capacity_kwh"))
        // Gemeinsames bleibt
        assertEquals(1.8f, app.getFloat("price_fuel", 0f))
        assertEquals("abc", app.getString("vehicle_id", null))
        assertTrue(app.getBoolean("bg_enabled", false))
    }

    @Test
    fun existingVehicleKeysWin() {
        val app = FakePrefs(mutableMapOf("tank_l" to 40f))
        val car = FakePrefs(mutableMapOf("tank_l" to 52f))

        Garage.moveKeys(app, car, Garage.VEHICLE_KEYS)

        assertEquals(52f, car.getFloat("tank_l", 0f))
        assertFalse(app.contains("tank_l"))
    }

    @Test
    fun nothingToMoveLeavesBothUntouched() {
        val app = FakePrefs(mutableMapOf("price_fuel" to 1.8f))
        val car = FakePrefs()
        Garage.moveKeys(app, car, Garage.VEHICLE_KEYS)
        assertTrue(car.all.isEmpty())
        assertEquals(1, app.all.size)
    }

    @Test
    fun parsesVehicleListFromCache() {
        val json = """{"_embedded":{"vehicles":[
            {"id":"v1","vin":"W0VAAA","label":"Astra"},
            {"id":"v2","vin":"W0VBBB"},
            {"vin":"ohne-id"}
        ]}}"""
        val list = Garage.parseVehicles(json)
        assertEquals(listOf("v1", "v2"), list.map { it.id })
        assertTrue(Garage.parseVehicles(null).isEmpty())
        assertTrue(Garage.parseVehicles("kaputt").isEmpty())
    }

    @Test
    fun displayNamePrefersOwnLabel() {
        val named = Vehicle("v1", "W0V", null, "Familienkutsche", null, null)
        val unnamed = Vehicle("v2", "W0V", null, " ", null, null)
        assertEquals("Familienkutsche", Garage.displayName(named, null))
        assertEquals("Mein Opel", Garage.displayName(unnamed, null))
        assertEquals("Mein Opel", Garage.displayName(null, null))
    }

    @Test
    fun safeIdsAreFileNames() {
        assertEquals("abc-123_X", Garage.safe("abc-123_X"))
        assertEquals("a_b_c", Garage.safe("a/b:c"))
        assertNull(Garage.safe("x").takeIf { it.contains('/') })
    }
}

/** Einfache SharedPreferences im Speicher für Tests. */
class FakePrefs(private val map: MutableMap<String, Any?> = mutableMapOf()) : SharedPreferences {
    override fun getAll(): MutableMap<String, *> = map.toMutableMap()
    override fun getString(key: String, defValue: String?) = map[key] as? String ?: defValue
    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String, defValues: MutableSet<String>?) = map[key] as? MutableSet<String> ?: defValues
    override fun getInt(key: String, defValue: Int) = map[key] as? Int ?: defValue
    override fun getLong(key: String, defValue: Long) = map[key] as? Long ?: defValue
    override fun getFloat(key: String, defValue: Float) = map[key] as? Float ?: defValue
    override fun getBoolean(key: String, defValue: Boolean) = map[key] as? Boolean ?: defValue
    override fun contains(key: String) = map.containsKey(key)
    override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}
    override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}

    override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
        private val put = mutableMapOf<String, Any?>()
        private val del = mutableSetOf<String>()
        private var clear = false
        override fun putString(key: String, value: String?) = apply { put[key] = value }
        override fun putStringSet(key: String, values: MutableSet<String>?) = apply { put[key] = values }
        override fun putInt(key: String, value: Int) = apply { put[key] = value }
        override fun putLong(key: String, value: Long) = apply { put[key] = value }
        override fun putFloat(key: String, value: Float) = apply { put[key] = value }
        override fun putBoolean(key: String, value: Boolean) = apply { put[key] = value }
        override fun remove(key: String) = apply { del += key }
        override fun clear() = apply { clear = true }
        override fun commit(): Boolean {
            if (clear) map.clear()
            del.forEach { map.remove(it) }
            map.putAll(put)
            return true
        }
        override fun apply() { commit() }
    }
}
