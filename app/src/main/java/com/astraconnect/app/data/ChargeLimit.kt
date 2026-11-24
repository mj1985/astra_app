package com.astraconnect.app.data

import android.content.Context
import android.content.SharedPreferences
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
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Ladelimit der App: Laden pausieren, sobald der Akku [percent] % erreicht.
 * [wakeUp] = die App darf das Auto für frische Daten wecken (aus z. B., wenn schon psa_car_controller weckt).
 */
data class ChargeLimitPrefs(val enabled: Boolean, val percent: Int, val wakeUp: Boolean = true) {
    companion object {
        const val DEFAULT_PCT = 80
        const val MIN_PCT = 50
        const val MAX_PCT = 95
    }
}

/** Was sich das Ladelimit zwischen zwei Prüfungen merkt. */
data class ChargeLimitState(
    /** In diesem Ladevorgang wurde schon pausiert. */
    val stopSent: Boolean = false,
    /** Eigene Weckbefehle der letzten 24 Stunden. */
    val wakeups: List<Instant> = emptyList(),
    /** Vom Nutzer programmierte Startzeit, bevor die App sie verlegt hat (Stunde, Minute). */
    val originalStart: Pair<Int, Int>? = null,
    val lastPostpone: Instant? = null,
    val lastRestoreTry: Instant? = null,
)

/**
 * Ladelimit in Software – für Autos, die selbst kein Limit können (Vorbild: psa_car_controller und die
 * Home-Assistant-Integration „Stellantis Vehicles“).
 *
 *  - **Pausieren:** Lädt das Auto und ist der Akku am Limit → „Laden pausieren“ (verzögertes Laden).
 *  - **Wecken:** Kurz vor dem Limit, wenn der gemeldete Akkustand veraltet ist (> 10 Min., beim
 *    Schnellladen > 5 Min.), wird das Auto geweckt, damit es einen frischen Stand meldet.
 *  - **Startzeit verlegen:** Ist der Akku am Limit und naht die programmierte Startzeit, wird sie auf
 *    „gerade eben“ gesetzt – sonst würde das Auto dann weiterladen. Liegt der Akku wieder unter dem Limit
 *    (oder ist das Limit aus), stellt die App die ursprüngliche Startzeit wieder her.
 *
 * Geprüft wird bei jedem Statusabruf (Hintergrund alle 15 Min., App) und zusätzlich zu geschätzten Zeitpunkten.
 */
object ChargeLimit {
    private const val TAG = "ChargeLimit"
    private const val CHECK_WORK = "charge_limit_check"
    private const val K_SENT = "charge_limit_sent"
    private const val K_WAKEUPS = "charge_limit_wakeups"
    private const val K_ORIG = "charge_limit_orig_start"
    private const val K_POSTPONED = "charge_limit_postponed_at"
    private const val K_RESTORE_TRY = "charge_limit_restore_try"

    val ROUTINE: Duration = Duration.ofMinutes(15)
    /** Ab hier gilt das Limit als „in Reichweite“ (Wecken lohnt sich). */
    const val NEAR_PCT = 10
    val NEAR_TIME: Duration = Duration.ofMinutes(30)
    val STALE: Duration = Duration.ofMinutes(10)
    val STALE_QUICK: Duration = Duration.ofMinutes(5)
    val WAKE_INTERVAL: Duration = Duration.ofMinutes(10)
    const val MAX_WAKEUPS_PER_DAY = 8
    /** So lange vor der programmierten Startzeit wird verlegt. */
    val POSTPONE_WINDOW: Duration = Duration.ofMinutes(30)

    private val mutex = Mutex()

    sealed interface Decision {
        data object None : Decision
        data object Stop : Decision
        data object WakeUp : Decision
        /** Limit wird voraussichtlich vor der nächsten Routine-Prüfung erreicht → dann nachsehen. */
        data class CheckIn(val delay: Duration) : Decision
        data class Postpone(val hour: Int, val minute: Int) : Decision
        data class Restore(val hour: Int, val minute: Int) : Decision
    }

    /** Reine Entscheidung (testbar). [now]/[zone] bestimmen „jetzt“ und die Uhrzeit für die Startzeit. */
    fun decide(
        prefs: ChargeLimitPrefs,
        status: VehicleStatus?,
        state: ChargeLimitState,
        now: Instant,
        zone: ZoneId = ZoneId.systemDefault(),
    ): Decision {
        val c = status?.charging
        val soc = status?.batteryPercent

        // Verlegte Startzeit zurückstellen: Limit aus oder Akku wieder darunter, Auto lädt nicht
        val orig = state.originalStart
        if (orig != null && c?.isCharging != true && (!prefs.enabled || (soc != null && soc < prefs.percent)) &&
            olderThan(state.lastRestoreTry, now, Duration.ofHours(1))
        ) return Decision.Restore(orig.first, orig.second)

        if (!prefs.enabled || c == null || soc == null || c.nativeLimitActive) return Decision.None

        if (c.isCharging) {
            if (state.stopSent) return Decision.None
            if (soc >= prefs.percent) return Decision.Stop
            val eta = minutesTo(prefs.percent, soc, c.remaining)
            val near = soc >= prefs.percent - NEAR_PCT || (eta != null && eta <= NEAR_TIME)
            val reportedAt = status.batteryUpdatedAt ?: status.updatedAt
            val staleAfter = if (c.mode.equals("Quick", true)) STALE_QUICK else STALE
            val stale = reportedAt != null && Duration.between(reportedAt, now) > staleAfter
            if (prefs.wakeUp && near && stale && canWake(state, now)) return Decision.WakeUp
            if (eta != null && eta < ROUTINE) return Decision.CheckIn(eta.plusMinutes(1))
            return Decision.None
        }

        // Pausiert und am Limit: kurz vor der programmierten Startzeit diese verlegen
        if (soc >= prefs.percent && c.plugged == true && c.status.equals("Stopped", true)) {
            val (h, m) = RemoteControl.ptTime(c.nextDelayedTime) ?: return Decision.None
            val local = now.atZone(zone).toLocalTime()
            val until = minutesUntil(local, LocalTime.of(h, m))
            if (until in 1..POSTPONE_WINDOW.toMinutes() && olderThan(state.lastPostpone, now, Duration.ofHours(2))) {
                // „Gerade eben“ → nächster Start erst in knapp 24 Stunden
                val t = local.minusMinutes(1)
                return Decision.Postpone(t.hour, t.minute)
            }
        }
        return Decision.None
    }

    /** Geschätzte Zeit bis [limit] %, linear aus der Restladezeit bis 100 %. */
    fun minutesTo(limit: Int, soc: Double, remainingToFull: Duration?): Duration? {
        if (remainingToFull == null || soc >= 100 || soc >= limit) return null
        val secs = remainingToFull.seconds * (limit - soc) / (100 - soc)
        return Duration.ofSeconds(secs.toLong().coerceAtLeast(0))
    }

    /** Minuten von [from] bis zum nächsten Erreichen von [to] (0–1439). */
    internal fun minutesUntil(from: LocalTime, to: LocalTime): Long =
        Math.floorMod(Duration.between(from.withSecond(0).withNano(0), to).toMinutes(), 24 * 60L)

    private fun canWake(state: ChargeLimitState, now: Instant): Boolean {
        val recent = state.wakeups.filter { Duration.between(it, now) < Duration.ofHours(24) }
        return recent.size < MAX_WAKEUPS_PER_DAY && recent.none { Duration.between(it, now) < WAKE_INTERVAL }
    }

    private fun olderThan(at: Instant?, now: Instant, d: Duration) = at == null || Duration.between(at, now) >= d

    // ------------------------------------------------------------------ Zustand

    fun loadState(prefs: SharedPreferences) = ChargeLimitState(
        stopSent = prefs.getBoolean(K_SENT, false),
        wakeups = prefs.getString(K_WAKEUPS, "").orEmpty().split(',').mapNotNull { it.toLongOrNull()?.let(Instant::ofEpochMilli) },
        originalStart = prefs.getString(K_ORIG, null)?.let { s ->
            val (h, m) = s.split(':').map { it.toIntOrNull() } + listOf(null, null)
            if (h != null && m != null) h to m else null
        },
        lastPostpone = prefs.getLong(K_POSTPONED, 0L).takeIf { it > 0 }?.let(Instant::ofEpochMilli),
        lastRestoreTry = prefs.getLong(K_RESTORE_TRY, 0L).takeIf { it > 0 }?.let(Instant::ofEpochMilli),
    )

    // ------------------------------------------------------------------ Ausführen

    /**
     * Nach jedem Statusabruf eines Fahrzeugs aufrufen (Hintergrund-Job, App). Führt die Entscheidung aus und
     * meldet Ergebnisse als Benachrichtigung. Einstellungen und Zustand gelten je Fahrzeug. Fehler werden
     * geloggt, nie geworfen.
     */
    suspend fun check(ctx: Context, vehicleId: String, status: VehicleStatus) = mutex.withLock {
        try {
            val settings = VehicleSettings(ctx, vehicleId)
            val prefs = settings.prefs
            // Neuer Ladevorgang → wieder pausieren dürfen
            if (status.charging?.isCharging != true) prefs.edit().putBoolean(K_SENT, false).commit()
            if (!VehicleProfile.load(ctx, vehicleId).powertrain.hasBattery) return@withLock

            val now = Instant.now()
            val v = Car(vehicleId, prefs)
            when (val d = decide(settings.chargeLimit, status, loadState(prefs), now)) {
                Decision.None -> {}
                is Decision.CheckIn -> scheduleCheck(ctx, vehicleId, d.delay)
                Decision.Stop -> {
                    prefs.edit().putBoolean(K_SENT, true).commit()
                    stop(ctx, v, status, settings.chargeLimit.percent)
                }
                Decision.WakeUp -> wakeUp(ctx, v, now)
                is Decision.Postpone -> postpone(ctx, v, status, settings.chargeLimit.percent, d, now)
                is Decision.Restore -> restore(ctx, v, d, now)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Ladelimit-Prüfung fehlgeschlagen", e)
        }
    }

    /** Fahrzeug, um das es gerade geht, mit seinen Einstellungen. */
    private class Car(val id: String, val prefs: SharedPreferences)

    private fun notify(ctx: Context, v: Car, title: String, text: String) = Notifier.chargeLimit(ctx, v.id, title, text)

    private suspend fun stop(ctx: Context, v: Car, status: VehicleStatus, percent: Int) {
        val soc = status.batteryPercent.fmtPct()
        val title = "Ladelimit $percent % erreicht"
        val result = send(ctx, v, title, "Akku bei $soc – ", RemoteCommand.charging(false, status.charging?.nextDelayedTime))
        val resume = RemoteControl.ptTime(status.charging?.nextDelayedTime) ?: (22 to 0)
        when {
            result == null -> v.prefs.edit().putBoolean(K_SENT, false).commit() // nicht gesendet → später erneut
            result.success -> notify(
                ctx, v, title,
                "Laden bei $soc pausiert. Vor dem nächsten Ladestart um ${hm(resume)} Uhr verlegt die App die Startzeit, " +
                    "damit das Auto nicht weiterlädt.",
            )
            result.final -> {
                v.prefs.edit().putBoolean(K_SENT, false).commit()
                notify(ctx, v, title, "Akku bei $soc – Pausieren fehlgeschlagen: ${result.label}")
            }
            else -> notify(ctx, v, title, "Akku bei $soc – Pausieren gesendet, aber keine Rückmeldung vom Auto.")
        }
    }

    private suspend fun wakeUp(ctx: Context, v: Car, now: Instant) {
        // Vor dem Senden merken, damit auch ein Fehlschlag das Kontingent schont
        val recent = loadState(v.prefs).wakeups.filter { Duration.between(it, now) < Duration.ofHours(24) } + now
        v.prefs.edit().putString(K_WAKEUPS, recent.joinToString(",") { it.toEpochMilli().toString() }).commit()
        send(ctx, v, null, "", RemoteCommand.wakeUp(), waitFor = Duration.ofSeconds(60))
        // Das Auto braucht einen Moment, bis der neue Stand bei Opel liegt
        scheduleCheck(ctx, v.id, Duration.ofMinutes(3))
    }

    private suspend fun postpone(
        ctx: Context, v: Car, status: VehicleStatus, percent: Int, d: Decision.Postpone, now: Instant,
    ) {
        val original = loadState(v.prefs).originalStart ?: RemoteControl.ptTime(status.charging?.nextDelayedTime)
        val title = "Ladestart verlegt"
        val result = send(ctx, v, "Ladestart verlegen fehlgeschlagen", "", RemoteCommand.delayedChargeAt(d.hour, d.minute))
        if (result?.success != true) return // beim nächsten Abruf im Zeitfenster erneut
        v.prefs.edit()
            .putString(K_ORIG, original?.let { hm(it) })
            .putLong(K_POSTPONED, now.toEpochMilli())
            .commit()
        notify(
            ctx, v, title,
            "Akku bei ${status.batteryPercent.fmtPct()} (Limit $percent %) – damit das Auto nicht weiterlädt, " +
                "startet das Laden jetzt erst um ${hm(d.hour to d.minute)} Uhr. " +
                (original?.let { "Die ursprüngliche Zeit ${hm(it)} Uhr stellt die App wieder her, sobald der Akku unter dem Limit ist." } ?: ""),
        )
    }

    private suspend fun restore(ctx: Context, v: Car, d: Decision.Restore, now: Instant) {
        v.prefs.edit().putLong(K_RESTORE_TRY, now.toEpochMilli()).commit()
        val result = send(ctx, v, null, "", RemoteCommand.delayedChargeAt(d.hour, d.minute))
        if (result?.success == true) {
            v.prefs.edit().remove(K_ORIG).remove(K_POSTPONED).commit()
            notify(ctx, v, "Ladestart wiederhergestellt", "Das Auto lädt wieder ab ${hm(d.hour to d.minute)} Uhr.")
        }
    }

    /**
     * Sendet einen Fernbefehl an das Fahrzeug und wartet auf die Rückmeldung. null = nicht gesendet (dann mit
     * [failTitle] gemeldet, falls angegeben). Ohne finale Rückmeldung in [waitFor] kommt ein nicht-finales Update zurück.
     */
    private suspend fun send(
        ctx: Context, v: Car, failTitle: String?, failPrefix: String, cmd: RemoteCommand, waitFor: Duration = Duration.ofSeconds(120),
    ): CommandUpdate? {
        val store = SecureStore(ctx)
        val http = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(40, TimeUnit.SECONDS).build()
        val remote = RemoteControl(store, AuthRepository(store, http), http)
        try {
            if (!remote.isActivated) {
                failTitle?.let { notify(ctx, v, it, "${failPrefix}Fernsteuerung ist nicht eingerichtet.") }
                return null
            }
            val vin = Garage.vehicle(ctx, v.id)?.vin?.ifBlank { null } ?: run {
                failTitle?.let { notify(ctx, v, it, "${failPrefix}kein Fahrzeug bekannt, bitte die App öffnen.") }
                return null
            }
            val id = remote.send(vin, cmd.service, cmd.params)
            return withTimeoutOrNull(waitFor.toMillis()) { remote.updates.first { it.correlationId == id && it.final } }
                ?: CommandUpdate(id, "", "keine Rückmeldung", final = false, success = false)
        } catch (e: AuthExpiredException) {
            Notifier.loginExpired(ctx)
            return null
        } catch (e: Exception) {
            failTitle?.let { notify(ctx, v, it, "${failPrefix}Senden fehlgeschlagen: ${e.message ?: e}") }
            Log.w(TAG, "Befehl ${cmd.service} fehlgeschlagen", e)
            return null
        } finally {
            remote.disconnect()
        }
    }

    /** Zusätzlicher Statusabruf (der Hintergrund-Job prüft dabei das Limit). Je Fahrzeug ein eigener Termin. */
    private fun scheduleCheck(ctx: Context, vehicleId: String, delay: Duration) {
        WorkManager.getInstance(ctx).enqueueUniqueWork(
            "${CHECK_WORK}_${Garage.safe(vehicleId)}", ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<SnapshotWorker>()
                .setInitialDelay(delay.seconds.coerceAtLeast(30), TimeUnit.SECONDS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build(),
        )
    }

    fun hm(t: Pair<Int, Int>) = "%02d:%02d".format(Locale.GERMANY, t.first, t.second)

    private fun Double?.fmtPct() = this?.let { "%.0f %%".format(Locale.GERMANY, it) } ?: "–"
}
