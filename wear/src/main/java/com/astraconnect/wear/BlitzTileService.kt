package com.astraconnect.wear

import androidx.concurrent.futures.CallbackToFutureAdapter
import androidx.wear.protolayout.ResourceBuilders
import androidx.wear.protolayout.TimelineBuilders
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService
import com.astraconnect.shared.WearContract
import com.astraconnect.shared.WearStatus
import com.astraconnect.wear.TileLayouts.Companion.ASK
import com.astraconnect.wear.TileLayouts.Companion.REFRESH
import com.astraconnect.wear.TileLayouts.Companion.SEND
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Tile: Akku-Ring (beim Verbrenner Tank), Reichweite, Ladestatus und Tasten für Klima und Verriegeln.
 * Befehle brauchen zwei Tipps (Taste → „Starten?“), damit am Handgelenk nichts aus Versehen losgeht.
 * Ein Tipp auf den Akkustand holt einen neuen Stand. Gezeichnet wird in [TileLayouts].
 */
class BlitzTileService : TileService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val layouts by lazy { TileLayouts(this) }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onTileRequest(requestParams: RequestBuilders.TileRequest): ListenableFuture<TileBuilders.Tile> =
        future { tile(requestParams) }

    override fun onTileResourcesRequest(requestParams: RequestBuilders.ResourcesRequest): ListenableFuture<ResourceBuilders.Resources> =
        future { layouts.resources() }

    private suspend fun tile(req: RequestBuilders.TileRequest): TileBuilders.Tile {
        val device = req.deviceConfiguration
        val clicked = req.currentState.lastClickableId
        val status = WatchState.status(this) ?: loadFromPhone()

        when {
            clicked.startsWith(SEND) && status != null -> send(clicked.removePrefix(SEND), status)
            clicked == REFRESH -> refresh()
        }

        val ask = clicked.removePrefix(ASK).takeIf { clicked.startsWith(ASK) && it in WearContract.COMMANDS }
        val now = System.currentTimeMillis()
        val layout = when {
            status == null -> layouts.noData(device, WatchState.error(this, now))
            ask != null -> layouts.confirm(device, status, ask)
            else -> layouts.main(
                device, status,
                TileUi(
                    sending = status.sending + WatchState.sentKeys(this, now),
                    error = WatchState.error(this, now),
                    refreshing = WatchState.refreshing(this, now),
                ),
            )
        }
        // Rückfrage nach 15 s verwerfen; „Sende …“ & Co. nach Ablauf neu zeichnen, sonst nur auf neue Daten hin
        val freshness = when {
            ask != null -> CONFIRM_MS
            WatchState.hasTimedState(this, now) -> TIMED_MS
            else -> 0L
        }
        return TileBuilders.Tile.Builder()
            .setResourcesVersion(TileLayouts.RES_VERSION)
            .setFreshnessIntervalMillis(freshness)
            .setTileTimeline(TimelineBuilders.Timeline.fromLayoutElement(layout))
            .build()
    }

    /** Noch nichts gespeichert (z. B. frisch installiert): vorhandenes Datenelement lesen, sonst das Handy bitten. */
    private suspend fun loadFromPhone(): WearStatus? {
        val json = withTimeoutOrNull(3_000) { runCatching { PhoneLink.fetchStatus(this@BlitzTileService) }.getOrNull() }
        if (json != null) {
            WatchState.saveStatus(this, json)
            return WearStatus.fromJson(json)
        }
        scope.launch { runCatching { PhoneLink.send(this@BlitzTileService, WearContract.PATH_REFRESH) } }
        return null
    }

    private suspend fun send(cmd: String, status: WearStatus) {
        if (cmd !in WearContract.COMMANDS) return
        WatchState.markSent(this, if (cmd == WearContract.LOCK) WearContract.KEY_LOCK else WearContract.KEY_CLIMATE)
        val ok = withTimeoutOrNull(5_000) {
            runCatching { PhoneLink.send(this@BlitzTileService, WearContract.PATH_COMMAND, WearContract.command(cmd, status.vehicleId)) }
                .getOrDefault(false)
        } == true
        if (!ok) {
            WatchState.clearSent(this)
            WatchState.setError(this, OFFLINE)
        }
    }

    private suspend fun refresh() {
        WatchState.markRefresh(this)
        val ok = withTimeoutOrNull(5_000) {
            runCatching { PhoneLink.send(this@BlitzTileService, WearContract.PATH_REFRESH) }.getOrDefault(false)
        } == true
        if (!ok) WatchState.setError(this, OFFLINE)
    }

    /** Ergebnis einer Coroutine als ListenableFuture – der Dienst bleibt gebunden, bis es da ist. */
    private fun <T> future(block: suspend () -> T): ListenableFuture<T> = CallbackToFutureAdapter.getFuture { completer ->
        val job = scope.launch {
            try {
                completer.set(block())
            } catch (e: Throwable) {
                completer.setException(e)
            }
        }
        completer.addCancellationListener({ job.cancel() }, Runnable::run)
        "BlitzTile"
    }

    private companion object {
        const val CONFIRM_MS = 15_000L
        const val TIMED_MS = 20_000L

        /** Kurz genug für den unteren Rand einer kleinen runden Uhr. */
        const val OFFLINE = "Handy offline"
    }
}
