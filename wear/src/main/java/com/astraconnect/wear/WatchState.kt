package com.astraconnect.wear

import android.content.ComponentName
import android.content.Context
import android.content.SharedPreferences
import androidx.wear.tiles.TileService
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester
import com.astraconnect.shared.WearStatus

/**
 * Zuletzt vom Handy empfangener Stand und kurzlebige Zustände der Uhr
 * (Befehl gerade abgeschickt, Abruf angefordert, Handy nicht erreichbar).
 */
object WatchState {
    private const val PREFS = "blitzlink"
    private const val K_STATUS = "status"
    private const val K_SENT = "sent"
    private const val K_REFRESH = "refresh"
    private const val K_ERROR = "error"

    /** So lange zeigt eine Taste „Sende …“, bis das Handy den laufenden Befehl selbst meldet. */
    private const val SENT_MS = 45_000L
    private const val REFRESH_MS = 30_000L
    private const val ERROR_MS = 60_000L

    private fun prefs(ctx: Context): SharedPreferences = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun status(ctx: Context): WearStatus? = prefs(ctx).getString(K_STATUS, null)?.let(WearStatus::fromJson)

    /** Neuer Stand vom Handy – ein angeforderter Abruf ist damit erledigt. */
    fun saveStatus(ctx: Context, json: String) {
        prefs(ctx).edit().putString(K_STATUS, json).remove(K_REFRESH).apply()
    }

    fun markSent(ctx: Context, key: String) = putTimed(ctx, K_SENT, key)
    fun clearSent(ctx: Context) = prefs(ctx).edit().remove(K_SENT).apply()
    fun sentKeys(ctx: Context, now: Long): Set<String> = setOfNotNull(timed(ctx, K_SENT, now, SENT_MS))

    fun markRefresh(ctx: Context) = putTimed(ctx, K_REFRESH, "1")
    fun refreshing(ctx: Context, now: Long): Boolean = timed(ctx, K_REFRESH, now, REFRESH_MS) != null

    fun setError(ctx: Context, text: String) = putTimed(ctx, K_ERROR, text)
    fun error(ctx: Context, now: Long): String? = timed(ctx, K_ERROR, now, ERROR_MS)

    /** Hält ein kurzlebiger Zustand an, braucht das Tile eine Aktualisierung, wenn er abläuft. */
    fun hasTimedState(ctx: Context, now: Long) =
        sentKeys(ctx, now).isNotEmpty() || refreshing(ctx, now) || error(ctx, now) != null

    private fun putTimed(ctx: Context, key: String, value: String) {
        prefs(ctx).edit().putString(key, "${System.currentTimeMillis()}|$value").apply()
    }

    private fun timed(ctx: Context, key: String, now: Long, maxAgeMs: Long): String? {
        val raw = prefs(ctx).getString(key, null) ?: return null
        val at = raw.substringBefore('|').toLongOrNull() ?: return null
        return raw.substringAfter('|').takeIf { now - at in 0 until maxAgeMs }
    }

    /** Tile und Complication neu zeichnen lassen. */
    fun refreshSurfaces(ctx: Context) {
        TileService.getUpdater(ctx).requestUpdate(BlitzTileService::class.java)
        ComplicationDataSourceUpdateRequester
            .create(ctx, ComponentName(ctx, BatteryComplicationService::class.java))
            .requestUpdateAll()
    }
}
