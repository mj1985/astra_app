package com.astraconnect.app.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.util.SizeF
import android.widget.RemoteViews
import com.astraconnect.app.MainActivity
import com.astraconnect.app.data.Garage
import com.astraconnect.app.data.Vehicle
import com.astraconnect.app.data.VehicleProfile
import com.astraconnect.app.data.VehicleSettings
import com.astraconnect.app.data.VehicleStatus
import com.astraconnect.app.wear.WearSync
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.Executors

/**
 * Gemeinsamer Unterbau der Homescreen-Widgets. Alle zeigen den zuletzt abgerufenen Stand
 * (App oder Hintergrund-Job) – sie wecken das Auto nicht.
 */
object Widgets {
    private const val TAG = "Widgets"

    /** Rendern liest Dateien (Fahrten, Messpunkte, Autobild) – nie auf dem Main-Thread. */
    private val executor = Executors.newSingleThreadExecutor()

    private val renderers: List<Pair<Class<out AppWidgetProvider>, (WidgetSource) -> List<Variant>>> = listOf(
        StatusWidget::class.java to { src -> listOf(Variant(0f, StatusWidget.render(src))) },
        ControlWidget::class.java to { src -> listOf(Variant(0f, ControlWidget.render(src))) },
        CarWidget::class.java to { src -> listOf(Variant(0f, CarWidget.render(src))) },
        RangeWidget::class.java to RangeWidget::render,
        TripWidget::class.java to TripWidget::render,
    )

    /**
     * Aktualisiert alle Widgets und schickt den Stand an die Uhr ([WearSync]).
     * Fehler hier dürfen den Abruf in App/Hintergrund-Job nie abbrechen.
     */
    fun updateAll(ctx: Context, done: (() -> Unit)? = null) {
        val app = ctx.applicationContext
        executor.execute {
            try {
                renderAll(app)
            } catch (e: Throwable) {
                Log.w(TAG, "Widget-Update fehlgeschlagen", e)
            }
            try {
                WearSync.push(app)
            } catch (e: Throwable) {
                Log.w(TAG, "Abgleich mit der Uhr fehlgeschlagen", e)
            } finally {
                done?.invoke()
            }
        }
    }

    private fun renderAll(ctx: Context) {
        val mgr = AppWidgetManager.getInstance(ctx)
        // Je Fahrzeug eine Datenquelle – Widgets desselben Fahrzeugs teilen sie sich
        val sources = mutableMapOf<String, WidgetSource>()
        for ((cls, render) in renderers) {
            val ids = mgr.getAppWidgetIds(ComponentName(ctx, cls))
            if (ids.isEmpty()) continue
            for ((vehicleId, group) in ids.groupBy { WidgetVehicles.vehicleFor(ctx, it) }) {
                val src = sources.getOrPut(vehicleId) { WidgetSource(ctx, vehicleId) }
                runCatching { apply(mgr, group.toIntArray(), render(src).sortedBy { it.minHeightDp }) }
                    .onFailure { Log.w(TAG, "${cls.simpleName} fehlgeschlagen", it) }
            }
        }
    }

    private fun apply(mgr: AppWidgetManager, ids: IntArray, variants: List<Variant>) {
        when {
            variants.size == 1 -> mgr.updateAppWidget(ids, variants[0].views)
            // Ab Android 12 wählt der Launcher selbst die größte Variante, die in die aktuelle Größe passt
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
                mgr.updateAppWidget(ids, RemoteViews(variants.associate { SizeF(1f, it.minHeightDp) to it.views }))
            // Davor: Höhe aus den Widget-Optionen (Hochformat = maximale Höhe)
            else -> ids.forEach { id ->
                val h = mgr.getAppWidgetOptions(id).getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT)
                mgr.updateAppWidget(id, (variants.lastOrNull { h >= it.minHeightDp } ?: variants.first()).views)
            }
        }
    }
}

/** Eine Ansicht eines Widgets, die ab [minHeightDp] Höhe verwendet wird (0 = kleinste). */
class Variant(val minHeightDp: Float, val views: RemoteViews)

/** Ab dieser Höhe (≈ 2 Zeilen) zeigen „Letzte Fahrt“ und „Reichweite“ die große Ansicht. */
internal const val TWO_ROWS_DP = 130f

/** Basis für alle Widget-Provider: Update im Hintergrund, Broadcast bleibt so lange offen. */
abstract class BaseWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val pending = goAsync()
        Widgets.updateAll(context) { pending.finish() }
    }

    /** Größe geändert → vor Android 12 muss die passende Variante selbst gewählt werden. */
    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) {
        val pending = goAsync()
        Widgets.updateAll(context) { pending.finish() }
    }

    override fun onDeleted(context: Context, ids: IntArray) {
        ids.forEach { WidgetVehicles.forget(context, it) }
    }
}

/**
 * Welches Fahrzeug ein Widget zeigt – beim Platzieren gewählt ([WidgetConfigActivity]). Ohne Wahl
 * (nur ein Fahrzeug, oder Widgets von vor der Mehr-Fahrzeug-Version) folgt es dem in der App gewählten.
 */
object WidgetVehicles {
    private fun key(widgetId: Int) = "widget_vehicle_$widgetId"

    fun vehicleFor(ctx: Context, widgetId: Int): String {
        val chosen = Garage.appPrefs(ctx).getString(key(widgetId), null)
        // Fahrzeug nicht mehr im Konto → dem gewählten folgen
        val known = Garage.vehicles(ctx)
        return chosen?.takeIf { id -> known.isEmpty() || known.any { it.id == id } } ?: Garage.selectedId(ctx)
    }

    /** Gewähltes Fahrzeug (null = dem in der App gewählten folgen). */
    fun chosen(ctx: Context, widgetId: Int): String? = Garage.appPrefs(ctx).getString(key(widgetId), null)

    fun set(ctx: Context, widgetId: Int, vehicleId: String?) {
        Garage.appPrefs(ctx).edit().apply { if (vehicleId == null) remove(key(widgetId)) else putString(key(widgetId), vehicleId) }.commit()
    }

    fun forget(ctx: Context, widgetId: Int) = set(ctx, widgetId, null)
}

/** Daten eines Fahrzeugs für einen Render-Durchgang – nur das wird geladen, was ein Widget tatsächlich braucht. */
class WidgetSource(val ctx: Context, val vehicleId: String) {
    /** Gemeinsame Einstellungen (u. a. laufende Widget-Befehle). */
    val prefs: SharedPreferences = Garage.appPrefs(ctx)
    val now: Instant = Instant.now()

    val vehicle: Vehicle? by lazy { Garage.vehicle(ctx, vehicleId) }

    val statusJson: JSONObject? by lazy { VehicleSettings(ctx, vehicleId).cachedStatus }
    val status: VehicleStatus? by lazy { statusJson?.let { runCatching { VehicleStatus.parse(it) }.getOrNull() } }

    /** Antriebsart und Größen – bestimmt, welche Teile die Widgets zeigen. */
    val profile: VehicleProfile by lazy { VehicleProfile.load(ctx, vehicleId) }

    /** Name wie in der App. */
    val name: String get() = Garage.displayName(vehicle, profile.model)

    fun views(layout: Int) = RemoteViews(ctx.packageName, layout)

    /** Öffnet die App mit diesem Fahrzeug. */
    fun openApp(): PendingIntent = PendingIntent.getActivity(
        ctx, 0,
        Intent(ctx, MainActivity::class.java)
            // Eigene Daten-URI je Fahrzeug, sonst teilen sich alle Widgets denselben PendingIntent
            .setData(Uri.parse("astra://fahrzeug/${Uri.encode(vehicleId)}"))
            .putExtra(MainActivity.EXTRA_VEHICLE, vehicleId)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}

private val standFormat = DateTimeFormatter.ofPattern("dd.MM. HH:mm").withZone(ZoneId.systemDefault())
private val todayFormat = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault())

/** „14:05“ für heute, sonst „20.11. 14:05“. */
internal fun Instant?.stand(now: Instant = Instant.now()): String {
    if (this == null) return ""
    val zone = ZoneId.systemDefault()
    val sameDay = atZone(zone).toLocalDate() == now.atZone(zone).toLocalDate()
    return (if (sameDay) todayFormat else standFormat).format(this)
}

internal const val COLOR_TEXT = 0xFFE6E6E6.toInt()
internal const val COLOR_ACCENT = 0xFFF7FF14.toInt()
internal const val COLOR_OK = 0xFF4CC27E.toInt()
internal const val COLOR_DARK = 0xFF1A1A00.toInt()
