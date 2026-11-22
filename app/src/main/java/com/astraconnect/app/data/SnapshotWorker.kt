package com.astraconnect.app.data

import android.content.Context
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
 * Holt im Hintergrund alle 15 Minuten den Fahrzeugstatus und speichert den Ladestand als
 * Messpunkt. Hat sich der Kilometerstand geändert, werden auch neue Fahrten übernommen.
 */
class SnapshotWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val store = SecureStore(ctx)
        val http = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(40, TimeUnit.SECONDS).build()
        val auth = AuthRepository(store, http)
        if (!auth.isLoggedIn) return Result.success()
        val prefs = ctx.getSharedPreferences("app", Context.MODE_PRIVATE)
        val vehicleId = prefs.getString("vehicle_id", null) ?: return Result.success()
        val api = StellantisApi(auth, http)
        val log = Stores.energy(ctx)

        return try {
            val before = log.all().lastOrNull()
            val json = api.status(vehicleId)
            val maint = prefs.getString("cache_maint", null)?.let { runCatching { org.json.JSONObject(it) }.getOrNull() }
            val status = VehicleStatus.parse(json, maint)
            log.record(status)
            prefs.edit().putString("cache_status", json.toString()).apply()
            Notifier.check(ctx, status)
            ChargeLimit.check(ctx, status)
            Widgets.updateAll(ctx)

            val moved = before?.mileage != null && status.mileageKm != null && status.mileageKm > before.mileage + 0.5
            if (moved) {
                val trips = Stores.trips(ctx)
                trips.merge(api.trips(vehicleId, trips.newestStart()?.minusSeconds(3600)))
                val book = Stores.places(ctx)
                val places = book.places()
                val rules = book.rules()
                trips.applyRules { PlaceBook.categoryFor(it, places, rules) }
                Widgets.updateAll(ctx) // neue Fahrt → „Letzte Fahrt“-Widget
            }
            runCatching { Backup(ctx).autoBackup() }
            Result.success()
        } catch (e: AuthExpiredException) {
            Notifier.loginExpired(ctx)
            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }

    companion object {
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
