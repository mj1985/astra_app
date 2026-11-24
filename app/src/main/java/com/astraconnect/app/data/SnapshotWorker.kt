package com.astraconnect.app.data

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.astraconnect.app.widget.Widgets
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Holt im Hintergrund alle 15 Minuten den Status aller Fahrzeuge des Kontos und speichert den Ladestand
 * als Messpunkt. Hat sich der Kilometerstand eines Fahrzeugs geändert, werden auch dessen neue Fahrten übernommen.
 */
class SnapshotWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val store = SecureStore(ctx)
        val http = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(40, TimeUnit.SECONDS).build()
        val auth = AuthRepository(store, http)
        if (!auth.isLoggedIn) return Result.success()
        // Ohne abgerufene Fahrzeugliste nur das gewählte (falls bekannt)
        val ids = Garage.vehicles(ctx).map { it.id }
            .ifEmpty { listOf(Garage.selectedId(ctx)).filter { it != Garage.UNASSIGNED } }
        if (ids.isEmpty()) return Result.success()
        val api = StellantisApi(auth, http)

        var failed = false
        for (id in ids) {
            try {
                poll(ctx, api, id)
            } catch (e: AuthExpiredException) {
                Notifier.loginExpired(ctx)
                return Result.success()
            } catch (e: Exception) {
                // Ein Fahrzeug ohne Antwort hält die anderen nicht auf
                Log.w(TAG, "Abruf für ${Garage.safe(id)} fehlgeschlagen", e)
                failed = true
            }
        }
        Widgets.updateAll(ctx)
        runCatching { Backup(ctx).autoBackup() }
        return if (failed) Result.retry() else Result.success()
    }

    private suspend fun poll(ctx: Context, api: StellantisApi, vehicleId: String) {
        val settings = VehicleSettings(ctx, vehicleId)
        val log = Stores.energy(ctx, vehicleId)
        val before = log.all().lastOrNull()
        val json = api.status(vehicleId)
        val status = VehicleStatus.parse(json, settings.cachedMaintenance)
        log.record(status)
        Stores.track(ctx, vehicleId).record(status)
        TripFollow.onStatus(ctx, status)
        settings.cacheStatus(json)
        Notifier.check(ctx, vehicleId, status)
        ChargeLimit.check(ctx, vehicleId, status)

        val moved = before?.mileage != null && status.mileageKm != null && status.mileageKm > before.mileage + 0.5
        if (moved) {
            val trips = Stores.trips(ctx, vehicleId)
            trips.merge(api.trips(vehicleId, trips.newestStart()?.minusSeconds(3600)))
            val book = Stores.places(ctx)
            val places = book.places()
            val rules = book.rules()
            trips.applyRules { PlaceBook.categoryFor(it, places, rules) }
        }
    }

    companion object {
        private const val TAG = "SnapshotWorker"
        private const val NAME = "energy_snapshots"

        fun schedule(ctx: Context, enabled: Boolean) {
            val wm = WorkManager.getInstance(ctx)
            if (!enabled) {
                wm.cancelUniqueWork(NAME)
                return
            }
            val req = PeriodicWorkRequestBuilder<SnapshotWorker>(15, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            wm.enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, req)
        }
    }
}
