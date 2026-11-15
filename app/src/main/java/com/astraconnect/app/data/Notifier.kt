package com.astraconnect.app.data

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.astraconnect.app.MainActivity
import com.astraconnect.app.R
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Locale

/**
 * Benachrichtigungen: Laden fertig/abgebrochen, Auto nachts offen, 12-V-Batterie schwach,
 * Wartung fällig, Anmeldung abgelaufen, Ergebnis von Widget-Befehlen.
 * Wird bei jedem Statusabruf (App und Hintergrund-Job) mit dem neuen Status aufgerufen.
 */
object Notifier {
    private const val CH_CHARGE = "charging"
    private const val CH_ALERT = "alerts"
    private const val CH_ACCOUNT = "account"
    private const val CH_REMOTE = "remote"

    private const val ID_CHARGE = 1
    private const val ID_NIGHT = 2
    private const val ID_12V = 3
    private const val ID_MAINT = 4
    private const val ID_LOGIN = 5
    private const val ID_REMOTE = 6

    fun ensureChannels(ctx: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = ctx.getSystemService(NotificationManager::class.java)
        listOf(
            NotificationChannel(CH_CHARGE, "Laden", NotificationManager.IMPORTANCE_DEFAULT),
            NotificationChannel(CH_ALERT, "Warnungen am Fahrzeug", NotificationManager.IMPORTANCE_HIGH),
            NotificationChannel(CH_ACCOUNT, "Anmeldung", NotificationManager.IMPORTANCE_DEFAULT),
            NotificationChannel(CH_REMOTE, "Fernbefehle vom Widget", NotificationManager.IMPORTANCE_DEFAULT),
        ).forEach(nm::createNotificationChannel)
    }

    private fun allowed(ctx: Context) =
        (Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) &&
            NotificationManagerCompat.from(ctx).areNotificationsEnabled()

    @Suppress("MissingPermission")
    private fun post(ctx: Context, id: Int, channel: String, title: String, text: String) {
        if (!allowed(ctx)) return
        ensureChannels(ctx)
        val open = PendingIntent.getActivity(
            ctx, 0, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(ctx, channel)
            .setSmallIcon(R.drawable.ic_stat_notify)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(ctx).notify(id, n)
    }

    private fun state(ctx: Context) = ctx.getSharedPreferences("notify_state", Context.MODE_PRIVATE)

    /** Vergleicht den neuen Status mit dem zuletzt gesehenen und meldet Änderungen. */
    @Synchronized
    fun check(ctx: Context, s: VehicleStatus) {
        val prefs = AppSettings(ctx).notify
        val st = state(ctx)
        val updated = s.updatedAt?.toEpochMilli() ?: 0L
        val lastSeen = st.getLong("last_update", 0L)
        if (updated != 0L && updated <= lastSeen) return // nichts Neues
        val edit = st.edit().putLong("last_update", updated)

        // --- Laden
        val nowCharging = s.charging?.isCharging == true
        val wasCharging = st.getBoolean("was_charging", false)
        if (wasCharging && !nowCharging) {
            val status = s.charging?.status?.lowercase()
            val full = status == "finished" || (s.batteryPercent ?: 0.0) >= 99
            if (full && prefs.chargeDone) {
                post(ctx, ID_CHARGE, CH_CHARGE, "Laden abgeschlossen",
                    "Akku ${pct(s.batteryPercent)} · elektrisch ${km(s.electricRangeKm)} Reichweite.")
            } else if (!full && prefs.chargeAborted) {
                val why = when {
                    s.charging?.plugged == false -> "Ladekabel wurde getrennt"
                    status == "failure" -> "Ladefehler gemeldet"
                    else -> "Ladevorgang gestoppt"
                }
                post(ctx, ID_CHARGE, CH_CHARGE, "Laden vorzeitig beendet",
                    "$why – Akku bei ${pct(s.batteryPercent)}.")
            }
        }
        edit.putBoolean("was_charging", nowCharging)

        val now = LocalDateTime.now(ZoneId.systemDefault())
        val today = LocalDate.now().toString()

        // --- Nachts offen (21–6 Uhr), einmal pro Nacht
        val night = now.hour >= 21 || now.hour < 6
        val nightKey = (if (now.hour < 6) LocalDate.now().minusDays(1) else LocalDate.now()).toString()
        val parked = s.moving != true && (s.ignition == null || s.ignition.equals("stop", true))
        val issues = listOfNotNull(
            if (s.isLocked == false) "nicht verriegelt" else null,
            if (s.anyDoorOpen) "Tür/Klappe offen" else null,
            if (s.anyWindowOpen) "Fenster offen" else null,
        )
        if (prefs.openAtNight && night && parked && issues.isNotEmpty() && st.getString("night", null) != nightKey) {
            post(ctx, ID_NIGHT, CH_ALERT, "Auto ist ${issues.joinToString(", ")}",
                "Stand ${s.updatedAt?.atZone(ZoneId.systemDefault())?.toLocalTime()?.withNano(0) ?: "unbekannt"}. " +
                    "Zum Verriegeln die App oder das Widget verwenden.")
            edit.putString("night", nightKey)
        }

        // --- 12-V-Batterie, einmal pro Tag
        val v12 = s.battery12V
        if (prefs.battery12V && v12 != null && v12 < prefs.battery12VThreshold && st.getString("v12", null) != today) {
            post(ctx, ID_12V, CH_ALERT, "12-V-Batterie schwach (${pct(v12)})",
                "Längere Standzeiten vermeiden oder die Batterie prüfen lassen.")
            edit.putString("v12", today)
        }

        // --- Wartung, höchstens einmal pro Woche
        val days = s.maintenanceDays
        val kmLeft = s.maintenanceKm
        val due = (days != null && days <= 30) || (kmLeft != null && kmLeft <= 1000)
        val lastMaint = st.getString("maint", null)?.let(LocalDate::parse)
        if (prefs.maintenance && due && (lastMaint == null || lastMaint.plusDays(7) <= LocalDate.now())) {
            val parts = listOfNotNull(days?.let { "in ${it.toInt()} Tagen" }, kmLeft?.let { "in ${km(it)}" })
            post(ctx, ID_MAINT, CH_ALERT, "Wartung bald fällig", "Nächste Wartung ${parts.joinToString(" bzw. ")}.")
            edit.putString("maint", today)
        }
        edit.apply()
    }

    fun loginExpired(ctx: Context) {
        val st = state(ctx)
        if (!AppSettings(ctx).notify.loginExpired || st.getBoolean("login_notified", false)) return
        post(ctx, ID_LOGIN, CH_ACCOUNT, "Opel-Anmeldung abgelaufen",
            "Bitte die App öffnen und neu anmelden – bis dahin werden keine Daten aufgezeichnet.")
        st.edit().putBoolean("login_notified", true).apply()
    }

    fun loginRestored(ctx: Context) {
        state(ctx).edit().putBoolean("login_notified", false).apply()
        NotificationManagerCompat.from(ctx).cancel(ID_LOGIN)
    }

    fun remoteResult(ctx: Context, title: String, text: String) = post(ctx, ID_REMOTE, CH_REMOTE, title, text)

    private fun pct(v: Double?) = v?.let { String.format(Locale.GERMANY, "%.0f %%", it) } ?: "–"
    private fun km(v: Double?) = v?.let { String.format(Locale.GERMANY, "%,.0f km", it) } ?: "–"
}
