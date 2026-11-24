package com.astraconnect.app.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import org.json.JSONObject
import java.io.File

/**
 * Mehrere Fahrzeuge im selben Opel-Konto.
 *
 * Je Fahrzeug getrennt: Fahrtenbuch, Ladestand-Messpunkte, Standorte, Tank-/Ladekosten, Fahrzeugbilder
 * (Dateien unter `fahrzeuge/<id>/`), dazu letzter Status, Antrieb/Akku/Tank und Ladelimit
 * (SharedPreferences `fahrzeug_<id>`) sowie der Stand der Benachrichtigungen (`notify_state_<id>`).
 * Gemeinsam bleiben Login, Fernsteuerung, Orte & Regeln, Preise und Benachrichtigungs-Einstellungen.
 *
 * Daten aus der Zeit mit nur einem Fahrzeug werden beim ersten Zugriff einmalig dem damals gewählten
 * Fahrzeug zugeordnet ([ensureMigrated]). Ist noch kein Fahrzeug bekannt (z. B. Sicherung vor dem ersten
 * Login eingespielt), landen sie bei [UNASSIGNED] und gehen später an das erste Fahrzeug ([adopt]).
 */
object Garage {
    private const val TAG = "Garage"

    /** Platzhalter, solange noch kein Fahrzeug aus dem Konto bekannt ist. */
    const val UNASSIGNED = "_"

    private const val K_SELECTED = "vehicle_id"
    private const val K_VEHICLES = "cache_vehicles"
    private const val K_MIGRATED = "garage_migrated"

    /** Dateien, die früher direkt im App-Ordner lagen und jetzt je Fahrzeug abgelegt werden. */
    internal val VEHICLE_FILES = listOf(
        "fahrtenbuch.json", "energy_log.json", "positionen.json", "kosten.json", "widget_car.png", "widget_car_002.png",
    )

    /** Einstellungen aus „app“, die jetzt je Fahrzeug gelten. */
    internal val VEHICLE_KEYS = listOf(
        "cache_status", "cache_maint",
        "powertrain", "capacity_kwh", "tank_l",
        "charge_limit_on", "charge_limit_pct", "auto_wakeup",
        "charge_limit_sent", "charge_limit_wakeups", "charge_limit_orig_start", "charge_limit_postponed_at", "charge_limit_restore_try",
        "widget_car_url", "widget_car_tried", "widget_car_002_url", "widget_car_002_tried",
    )

    /** Bleibt beim Konto: „Anmeldung abgelaufen“ wird nur einmal gemeldet, egal wie viele Fahrzeuge. */
    private const val ACCOUNT_NOTIFY_KEY = "login_notified"

    @Volatile private var migrated = false

    fun appPrefs(ctx: Context): SharedPreferences = ctx.getSharedPreferences("app", Context.MODE_PRIVATE)

    // ------------------------------------------------------------------ Fahrzeuge

    /** Fahrzeuge aus dem zuletzt abgerufenen Konto (leer, solange nichts abgerufen wurde). */
    fun vehicles(ctx: Context): List<Vehicle> = parseVehicles(appPrefs(ctx).getString(K_VEHICLES, null))

    internal fun parseVehicles(json: String?): List<Vehicle> =
        json?.let { runCatching { JSONObject(it) }.getOrNull() }
            .obj("_embedded").arr("vehicles").objects()
            .mapNotNull { runCatching { Vehicle.parse(it) }.getOrNull() }
            .filter { it.id.isNotBlank() }

    /** In der App gewähltes Fahrzeug ([UNASSIGNED], solange keins bekannt ist). */
    fun selectedId(ctx: Context): String = appPrefs(ctx).getString(K_SELECTED, null)?.takeIf { it.isNotBlank() } ?: UNASSIGNED

    fun select(ctx: Context, id: String) {
        appPrefs(ctx).edit().putString(K_SELECTED, id).apply()
    }

    /** Fahrzeug zur [id]; für das gewählte bzw. [UNASSIGNED] notfalls das erste des Kontos. */
    fun vehicle(ctx: Context, id: String): Vehicle? {
        val list = vehicles(ctx)
        return list.firstOrNull { it.id == id } ?: if (id == UNASSIGNED || id == selectedId(ctx)) list.firstOrNull() else null
    }

    /** Anzeigename: eigener Name aus dem Opel-Konto, sonst das Modell, sonst „Mein Opel“. */
    fun displayName(vehicle: Vehicle?, model: CarModel? = CarModels.find(vehicle?.vin)): String =
        vehicle?.label?.takeIf { it.isNotBlank() } ?: model?.let { "Opel ${it.name}" } ?: "Mein Opel"

    fun name(ctx: Context, id: String): String = displayName(vehicle(ctx, id))

    /** Mehr als ein Fahrzeug im Konto (dann nennen Benachrichtigungen das Fahrzeug). */
    fun multiple(ctx: Context): Boolean = vehicles(ctx).size > 1

    // ------------------------------------------------------------------ Ablage je Fahrzeug

    /** Fahrzeug-ID als Datei-/Schlüsselname (Opel-IDs sind schon unkritisch, aber sicher ist sicher). */
    fun safe(id: String): String = id.replace(Regex("[^A-Za-z0-9_-]"), "_")

    fun root(ctx: Context): File = File(ctx.filesDir, "fahrzeuge")

    fun dir(ctx: Context, id: String): File {
        ensureMigrated(ctx)
        return File(root(ctx), safe(id))
    }

    fun prefs(ctx: Context, id: String): SharedPreferences {
        ensureMigrated(ctx)
        return rawPrefs(ctx, id)
    }

    fun notifyState(ctx: Context, id: String): SharedPreferences {
        ensureMigrated(ctx)
        return rawNotifyState(ctx, id)
    }

    private fun rawPrefs(ctx: Context, id: String) = ctx.getSharedPreferences("fahrzeug_${safe(id)}", Context.MODE_PRIVATE)
    private fun rawNotifyState(ctx: Context, id: String) = ctx.getSharedPreferences("notify_state_${safe(id)}", Context.MODE_PRIVATE)
    private fun accountNotifyState(ctx: Context) = ctx.getSharedPreferences("notify_state", Context.MODE_PRIVATE)

    /** IDs mit gespeicherten Daten oder im Konto – für die Datensicherung. */
    fun knownIds(ctx: Context): List<String> {
        ensureMigrated(ctx)
        val fromAccount = vehicles(ctx).map { it.id }
        val safeKnown = fromAccount.map(::safe).toSet()
        val fromDisk = root(ctx).listFiles()?.filter { it.isDirectory && (it.listFiles()?.isNotEmpty() == true) }
            ?.map { it.name }?.filter { it !in safeKnown }.orEmpty()
        return (fromAccount + listOf(selectedId(ctx)).filter { safe(it) !in safeKnown } + fromDisk).distinct()
    }

    // ------------------------------------------------------------------ Umzug alter Daten

    /** Einmalig: Daten aus der Zeit mit nur einem Fahrzeug dem gewählten Fahrzeug zuordnen. */
    fun ensureMigrated(ctx: Context) {
        if (migrated) return
        synchronized(this) {
            if (migrated) return
            val app = appPrefs(ctx)
            if (!app.getBoolean(K_MIGRATED, false)) {
                val target = selectedId(ctx)
                runCatching {
                    moveFiles(ctx.filesDir, File(root(ctx), safe(target)), VEHICLE_FILES)
                    moveKeys(app, rawPrefs(ctx, target), VEHICLE_KEYS)
                    val st = accountNotifyState(ctx)
                    moveKeys(st, rawNotifyState(ctx, target), st.all.keys.filter { it != ACCOUNT_NOTIFY_KEY })
                }.onFailure { Log.w(TAG, "Umzug der Fahrzeugdaten fehlgeschlagen", it) }
                    .onSuccess { app.edit().putBoolean(K_MIGRATED, true).commit() }
            }
            migrated = true
        }
    }

    /**
     * Sobald ein echtes Fahrzeug bekannt ist: Daten, die noch bei [UNASSIGNED] liegen, übernehmen.
     * Vorhandenes beim Fahrzeug wird nie überschrieben. Danach müssen die Speicher neu geöffnet werden.
     */
    fun adopt(ctx: Context, id: String) {
        if (id == UNASSIGNED) return
        ensureMigrated(ctx)
        synchronized(this) {
            val from = File(root(ctx), safe(UNASSIGNED))
            val fromPrefs = rawPrefs(ctx, UNASSIGNED)
            val fromState = rawNotifyState(ctx, UNASSIGNED)
            val hasFiles = from.listFiles()?.isNotEmpty() == true
            if (!hasFiles && fromPrefs.all.isEmpty() && fromState.all.isEmpty()) return
            moveFiles(from, File(root(ctx), safe(id)), from.listFiles()?.map { it.name }.orEmpty())
            moveKeys(fromPrefs, rawPrefs(ctx, id), fromPrefs.all.keys.toList())
            moveKeys(fromState, rawNotifyState(ctx, id), fromState.all.keys.toList())
            Stores.forget(UNASSIGNED)
            Stores.forget(id)
        }
    }

    /**
     * Verschiebt die genannten Dateien von [from] nach [to]. Gibt es am Ziel schon eine Datei gleichen Namens,
     * bleibt die Quelle liegen – es wird nie etwas überschrieben oder gelöscht.
     */
    internal fun moveFiles(from: File, to: File, names: List<String>) {
        for (name in names) {
            val src = File(from, name)
            if (!src.isFile) continue
            val dst = File(to, name)
            if (dst.exists()) continue
            to.mkdirs()
            if (!src.renameTo(dst)) {
                // Andere Partition o. Ä.: kopieren, erst nach erfolgreichem Kopieren die Quelle entfernen
                src.copyTo(dst)
                if (dst.length() == src.length()) src.delete()
            }
        }
    }

    /** Übernimmt Schlüssel, die am Ziel noch fehlen, und entfernt sie danach an der Quelle. */
    internal fun moveKeys(from: SharedPreferences, to: SharedPreferences, keys: Collection<String>) {
        val all = from.all
        val present = keys.filter { all.containsKey(it) }
        if (present.isEmpty()) return
        val target = to.all
        val e = to.edit()
        for (k in present) {
            if (target.containsKey(k)) continue
            when (val v = all[k]) {
                is String -> e.putString(k, v)
                is Boolean -> e.putBoolean(k, v)
                is Int -> e.putInt(k, v)
                is Long -> e.putLong(k, v)
                is Float -> e.putFloat(k, v)
                is Set<*> -> e.putStringSet(k, v.filterIsInstance<String>().toSet())
            }
        }
        // Ziel zuerst sicher schreiben, dann die Quelle aufräumen
        if (!e.commit()) return
        from.edit().apply { present.forEach { remove(it) } }.commit()
    }
}
