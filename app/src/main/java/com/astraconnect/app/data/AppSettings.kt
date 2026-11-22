package com.astraconnect.app.data

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject

/** Preise für die Kostenberechnung. */
data class Prices(
    val fuelPerLiter: Double,
    val powerPerKwh: Double,
    /** Ladeverluste in % – so viel mehr Strom kommt aus der Steckdose als im Akku landet. */
    val chargeLossPct: Double,
) {
    fun electricCost(kwhFromBattery: Double) = kwhFromBattery * (1 + chargeLossPct / 100.0) * powerPerKwh
}

/** Welche Benachrichtigungen gewünscht sind. */
data class NotifyPrefs(
    val chargeDone: Boolean = true,
    val chargeAborted: Boolean = true,
    val openAtNight: Boolean = true,
    val battery12V: Boolean = true,
    val maintenance: Boolean = true,
    val loginExpired: Boolean = true,
    val battery12VThreshold: Double = 50.0,
)

/** Einstellungen (SharedPreferences „app“) – gemeinsam für App, Hintergrund-Job und Widget. */
class AppSettings(context: Context) {
    val prefs: SharedPreferences = context.getSharedPreferences("app", Context.MODE_PRIVATE)

    var prices: Prices
        get() = Prices(
            prefs.getFloat("price_fuel", 1.75f).toDouble(),
            prefs.getFloat("price_power", 0.35f).toDouble(),
            prefs.getFloat("charge_loss", 10f).toDouble(),
        )
        set(v) {
            prefs.edit().putFloat("price_fuel", v.fuelPerLiter.toFloat()).putFloat("price_power", v.powerPerKwh.toFloat())
                .putFloat("charge_loss", v.chargeLossPct.toFloat()).apply()
        }

    /** Selbst eingetragener Tankinhalt; null = aus Modellliste bzw. Standard (siehe [VehicleProfile]). */
    var tankLitersManual: Double?
        get() = if (prefs.contains("tank_l")) prefs.getFloat("tank_l", 0f).toDouble() else null
        set(v) = prefs.edit().apply { if (v == null) remove("tank_l") else putFloat("tank_l", v.toFloat()) }.apply()

    /** Selbst eingetragene nutzbare Akkukapazität; null = aus Modellliste bzw. Standard. */
    var capacityKwhManual: Double?
        get() = if (prefs.contains("capacity_kwh")) prefs.getFloat("capacity_kwh", 0f).toDouble() else null
        set(v) = prefs.edit().apply { if (v == null) remove("capacity_kwh") else putFloat("capacity_kwh", v.toFloat()) }.apply()

    /** Selbst gewählte Antriebsart; null = automatisch erkennen. */
    var powertrainManual: Powertrain?
        get() = Powertrain.of(prefs.getString("powertrain", null))
        set(v) = prefs.edit().putString("powertrain", v?.name).apply()

    var notify: NotifyPrefs
        get() = NotifyPrefs(
            chargeDone = prefs.getBoolean("n_charge_done", true),
            chargeAborted = prefs.getBoolean("n_charge_abort", true),
            openAtNight = prefs.getBoolean("n_open_night", true),
            battery12V = prefs.getBoolean("n_12v", true),
            maintenance = prefs.getBoolean("n_maint", true),
            loginExpired = prefs.getBoolean("n_login", true),
            battery12VThreshold = prefs.getFloat("n_12v_threshold", 50f).toDouble(),
        )
        set(v) {
            prefs.edit()
                .putBoolean("n_charge_done", v.chargeDone).putBoolean("n_charge_abort", v.chargeAborted)
                .putBoolean("n_open_night", v.openAtNight).putBoolean("n_12v", v.battery12V)
                .putBoolean("n_maint", v.maintenance).putBoolean("n_login", v.loginExpired)
                .putFloat("n_12v_threshold", v.battery12VThreshold.toFloat())
                .apply()
        }

    /** Einträge der Leiste unten (Namen der Ziele, ohne die feste Startseite). null = Standard. */
    var bottomBar: List<String>?
        get() = prefs.getString("bottom_bar", null)?.split(',')?.filter { it.isNotBlank() }
        set(v) = prefs.edit().putString("bottom_bar", v?.joinToString(",")).apply()

    /** Ordner (SAF-Baum-URI) für die automatische Sicherung, null = aus. */
    var backupTreeUri: String?
        get() = prefs.getString("backup_tree", null)
        set(v) = prefs.edit().putString("backup_tree", v).apply()

    var lastBackupAt: Long
        get() = prefs.getLong("backup_last", 0L)
        set(v) = prefs.edit().putLong("backup_last", v).apply()

    /** Einstellungen, die in die Datensicherung gehören. */
    fun toJson(): JSONObject = JSONObject()
        .put("price_fuel", prices.fuelPerLiter).put("price_power", prices.powerPerKwh).put("charge_loss", prices.chargeLossPct)
        .put("tank_l", tankLitersManual ?: JSONObject.NULL)
        .put("capacity_kwh", capacityKwhManual ?: JSONObject.NULL)
        .put("powertrain", powertrainManual?.name ?: JSONObject.NULL)
        .put("bottom_bar", bottomBar?.joinToString(",") ?: JSONObject.NULL)

    fun restore(o: JSONObject) {
        o.str("bottom_bar")?.let { s -> bottomBar = s.split(',').filter { it.isNotBlank() } }
        prices = Prices(
            o.num("price_fuel") ?: prices.fuelPerLiter,
            o.num("price_power") ?: prices.powerPerKwh,
            o.num("charge_loss") ?: prices.chargeLossPct,
        )
        o.num("tank_l")?.let { tankLitersManual = it }
        o.num("capacity_kwh")?.let { capacityKwhManual = it }
        Powertrain.of(o.str("powertrain"))?.let { powertrainManual = it }
    }
}
