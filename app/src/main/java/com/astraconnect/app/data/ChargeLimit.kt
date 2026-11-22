package com.astraconnect.app.data

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import java.time.Duration
import java.util.concurrent.TimeUnit

/** Ladelimit der App: Laden pausieren, sobald der Akku [percent] % erreicht. */
data class ChargeLimitPrefs(val enabled: Boolean, val percent: Int) {
    companion object {
        const val DEFAULT_PCT = 80
        const val MIN_PCT = 50
        const val MAX_PCT = 95
    }
}

/**
 * Ladelimit in Software – für Autos, die selbst kein Limit können (wie in der Home-Assistant-Integration
 * „Stellantis Vehicles“): Läuft das Laden und ist der Ladestand am Limit, schickt die App „Laden pausieren“
 * (verzögertes Laden ab der programmierten Startzeit). Startet das Auto dort erneut, wird wieder pausiert.
 *
 * Geprüft wird bei jedem Statusabruf (Hintergrund alle 15 Min., App). Kurz vor dem Limit wird eine
 * zusätzliche Prüfung zum geschätzten Zeitpunkt eingeplant.
 */
object ChargeLimit {
    private const val TAG = "ChargeLimit"
    private const val K_SENT = "charge_limit_sent"
    private const val CHECK_WORK = "charge_limit_check"
    private val mutex = Mutex()

    sealed interface Decision {
        data object None : Decision
        data object Stop : Decision
        /** Limit wird voraussichtlich vor der nächsten Routine-Prüfung erreicht → dann nachsehen. */
        data class CheckIn(val delay: Duration) : Decision
    }

    /**
     * Reine Entscheidung (testbar). [alreadySent]: in diesem Ladevorgang wurde schon pausiert.
     * [routine]: Abstand der regelmäßigen Prüfung im Hintergrund.
     */
    fun decide(
        prefs: ChargeLimitPrefs,
        status: VehicleStatus?,
        alreadySent: Boolean,
        routine: Duration = Duration.ofMinutes(15),
    ): Decision {
        val c = status?.charging ?: return Decision.None
        val soc = status.batteryPercent ?: return Decision.None
        if (!prefs.enabled || !c.isCharging || c.nativeLimitActive || alreadySent) return Decision.None
        if (soc >= prefs.percent) return Decision.Stop
        val eta = minutesTo(prefs.percent, soc, c.remaining) ?: return Decision.None
        return if (eta < routine) Decision.CheckIn(eta.plusMinutes(1)) else Decision.None
    }

    /** Geschätzte Zeit bis [limit] %, linear aus der Restladezeit bis 100 %. */
    fun minutesTo(limit: Int, soc: Double, remainingToFull: Duration?): Duration? {
        if (remainingToFull == null || soc >= 100 || soc >= limit) return null
        val secs = remainingToFull.seconds * (limit - soc) / (100 - soc)
        return Duration.ofSeconds(secs.toLong().coerceAtLeast(0))
    }

    /**
     * Nach jedem Statusabruf aufrufen (Hintergrund-Job, App). Sendet ggf. den Pausier-Befehl und meldet
     * das Ergebnis als Benachrichtigung. Fehler werden geloggt, nie geworfen.
     */
    suspend fun check(ctx: Context, status: VehicleStatus) = mutex.withLock {
        try {
            val settings = AppSettings(ctx)
            val prefs = settings.prefs
            val limit = settings.chargeLimit
            // Neuer Ladevorgang → wieder pausieren dürfen
            if (status.charging?.isCharging != true) prefs.edit().putBoolean(K_SENT, false).commit()
            if (!VehicleProfile.load(ctx).powertrain.hasBattery) return@withLock

            when (val d = decide(limit, status, prefs.getBoolean(K_SENT, false))) {
                Decision.None -> {}
                is Decision.CheckIn -> scheduleCheck(ctx, d.delay)
                Decision.Stop -> {
                    prefs.edit().putBoolean(K_SENT, true).commit()
                    stop(ctx, status, limit.percent)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Ladelimit-Prüfung fehlgeschlagen", e)
        }
    }

    private suspend fun stop(ctx: Context, status: VehicleStatus, percent: Int) {
        val store = SecureStore(ctx)
        val http = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(40, TimeUnit.SECONDS).build()
        val auth = AuthRepository(store, http)
        val remote = RemoteControl(store, auth, http)
        val soc = status.batteryPercent.fmtPct()
        val title = "Ladelimit $percent % erreicht"
        try {
            if (!remote.isActivated) {
                Notifier.chargeLimit(ctx, title, "Akku bei $soc – Laden konnte nicht pausiert werden: Fernsteuerung ist nicht eingerichtet.")
                return
            }
            val vin = vin(ctx) ?: run {
                Notifier.chargeLimit(ctx, title, "Akku bei $soc – kein Fahrzeug bekannt, bitte die App öffnen.")
                return
            }
            val c = RemoteCommand.charging(false, status.charging?.nextDelayedTime)
            val id = remote.send(vin, c.service, c.params)
            val result = withTimeoutOrNull(120_000) { remote.updates.first { it.correlationId == id && it.final } }
            val resume = RemoteControl.ptTime(status.charging?.nextDelayedTime) ?: (22 to 0)
            when {
                result?.success == true -> Notifier.chargeLimit(
                    ctx, title,
                    "Laden bei $soc pausiert. Das Auto lädt ab %02d:%02d Uhr weiter – die App pausiert dann erneut.".format(resume.first, resume.second),
                )
                result == null -> Notifier.chargeLimit(ctx, title, "Akku bei $soc – Pausieren gesendet, aber keine Rückmeldung vom Auto.")
                else -> {
                    // Beim nächsten Abruf noch einmal versuchen
                    AppSettings(ctx).prefs.edit().putBoolean(K_SENT, false).commit()
                    Notifier.chargeLimit(ctx, title, "Akku bei $soc – Pausieren fehlgeschlagen: ${result.label}")
                }
            }
        } catch (e: AuthExpiredException) {
            Notifier.loginExpired(ctx)
        } catch (e: Exception) {
            AppSettings(ctx).prefs.edit().putBoolean(K_SENT, false).commit()
            Notifier.chargeLimit(ctx, title, "Akku bei $soc – Pausieren fehlgeschlagen: ${e.message ?: e}")
        } finally {
            remote.disconnect()
        }
    }

    /** Zusätzlicher Statusabruf zum geschätzten Zeitpunkt (der Hintergrund-Job prüft dabei das Limit). */
    private fun scheduleCheck(ctx: Context, delay: Duration) {
        WorkManager.getInstance(ctx).enqueueUniqueWork(
            CHECK_WORK, ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<SnapshotWorker>()
                .setInitialDelay(delay.seconds.coerceAtLeast(30), TimeUnit.SECONDS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build(),
        )
    }

    private fun vin(ctx: Context): String? {
        val prefs = AppSettings(ctx).prefs
        val id = prefs.getString("vehicle_id", null)
        val list = prefs.getString("cache_vehicles", null)
            ?.let { runCatching { org.json.JSONObject(it) }.getOrNull() }
            .obj("_embedded").arr("vehicles").objects()
        return (list.firstOrNull { it.optString("id") == id } ?: list.firstOrNull())?.optString("vin")?.ifBlank { null }
    }

    private fun Double?.fmtPct() = this?.let { "%.0f %%".format(java.util.Locale.GERMANY, it) } ?: "–"
}
