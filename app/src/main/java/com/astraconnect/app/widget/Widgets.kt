package com.astraconnect.app.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.util.SizeF
import android.widget.RemoteViews
import com.astraconnect.app.MainActivity
import com.astraconnect.app.data.VehicleStatus
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
        RangeWidget::class.java to RangeWidget::render,
        TripWidget::class.java to TripWidget::render,
    )

    /** Aktualisiert alle Widgets. Fehler hier dürfen den Abruf in App/Hintergrund-Job nie abbrechen. */
    fun updateAll(ctx: Context, done: (() -> Unit)? = null) {
        val app = ctx.applicationContext
        executor.execute {
            try {
                renderAll(app)
            } catch (e: Throwable) {
                Log.w(TAG, "Widget-Update fehlgeschlagen", e)
            } finally {
                done?.invoke()
            }
        }
    }

    private fun renderAll(ctx: Context) {
        val mgr = AppWidgetManager.getInstance(ctx)
        val src = WidgetSource(ctx)
        for ((cls, render) in renderers) {
            val ids = mgr.getAppWidgetIds(ComponentName(ctx, cls))
            if (ids.isEmpty()) continue
            runCatching { apply(mgr, ids, render(src).sortedBy { it.minHeightDp }) }
                .onFailure { Log.w(TAG, "${cls.simpleName} fehlgeschlagen", it) }
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
}

/** Daten für ein Render-Durchgang – nur das wird geladen, was ein Widget tatsächlich braucht. */
class WidgetSource(val ctx: Context) {
    val prefs: SharedPreferences = ctx.getSharedPreferences("app", Context.MODE_PRIVATE)
    val now: Instant = Instant.now()

    val statusJson: JSONObject? by lazy {
        prefs.getString("cache_status", null)?.let { runCatching { JSONObject(it) }.getOrNull() }
    }
    val status: VehicleStatus? by lazy { statusJson?.let { runCatching { VehicleStatus.parse(it) }.getOrNull() } }

    fun views(layout: Int) = RemoteViews(ctx.packageName, layout)

    fun openApp(): PendingIntent = PendingIntent.getActivity(
        ctx, 0, Intent(ctx, MainActivity::class.java),
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
