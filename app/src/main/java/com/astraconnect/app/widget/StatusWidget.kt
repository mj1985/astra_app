package com.astraconnect.app.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.astraconnect.app.MainActivity
import com.astraconnect.app.R
import com.astraconnect.app.data.AuthExpiredException
import com.astraconnect.app.data.AuthRepository
import com.astraconnect.app.data.Notifier
import com.astraconnect.app.data.RemoteControl
import com.astraconnect.app.data.SecureStore
import com.astraconnect.app.data.VehicleStatus
import com.astraconnect.app.data.arr
import com.astraconnect.app.data.obj
import com.astraconnect.app.data.objects
import com.astraconnect.app.data.str
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import org.json.JSONObject
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Homescreen-Widget: Ladestand, Reichweite, Verriegelung und Taste „Zu“.
 * Zeigt den zuletzt abgerufenen Status (App oder Hintergrund-Job) – es weckt das Auto nicht.
 */
class StatusWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = render(context, manager, ids)

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_LOCK) {
            context.getSharedPreferences("app", Context.MODE_PRIVATE).edit().putBoolean(K_PENDING, true).apply()
            updateAll(context)
            WorkManager.getInstance(context).enqueueUniqueWork(
                "widget_lock", ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<LockWorker>()
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .build(),
            )
        }
    }

    companion object {
        const val ACTION_LOCK = "com.astraconnect.app.WIDGET_LOCK"
        private const val K_PENDING = "widget_lock_pending"

        fun updateAll(ctx: Context) {
            val mgr = AppWidgetManager.getInstance(ctx)
            val ids = mgr.getAppWidgetIds(ComponentName(ctx, StatusWidget::class.java))
            if (ids.isNotEmpty()) render(ctx, mgr, ids)
        }

        private fun render(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
            val prefs = ctx.getSharedPreferences("app", Context.MODE_PRIVATE)
            val status = prefs.getString("cache_status", null)
                ?.let { runCatching { VehicleStatus.parse(JSONObject(it)) }.getOrNull() }
            val pending = prefs.getBoolean(K_PENDING, false)

            val views = RemoteViews(ctx.packageName, R.layout.widget_status)
            fun f(v: Double?, unit: String) = v?.let { String.format(Locale.GERMANY, "%.0f$unit", it) } ?: "–"
            views.setTextViewText(R.id.w_soc, f(status?.batteryPercent, " %"))
            views.setTextViewText(R.id.w_charging, if (status?.charging?.isCharging == true) "⚡ lädt" else "")
            views.setTextViewText(
                R.id.w_range,
                "${f(status?.electricRangeKm, " km")} elektrisch · ${f(status?.totalRangeKm, " km")} gesamt",
            )
            views.setTextViewText(
                R.id.w_lock,
                when {
                    pending -> "Verriegeln wird gesendet …"
                    status == null -> "Noch keine Daten"
                    else -> status.lockLabel + if (status.anyDoorOpen) " · Tür offen" else ""
                },
            )
            views.setTextViewText(
                R.id.w_updated,
                status?.updatedAt?.let { "Stand " + DateTimeFormatter.ofPattern("dd.MM. HH:mm").withZone(ZoneId.systemDefault()).format(it) } ?: "",
            )

            val open = PendingIntent.getActivity(
                ctx, 0, Intent(ctx, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            views.setOnClickPendingIntent(R.id.widget_root, open)
            val lock = PendingIntent.getBroadcast(
                ctx, 1, Intent(ctx, StatusWidget::class.java).setAction(ACTION_LOCK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            views.setOnClickPendingIntent(R.id.w_lock_button, lock)
            mgr.updateAppWidget(ids, views)
        }

        internal fun setPending(ctx: Context, pending: Boolean) {
            ctx.getSharedPreferences("app", Context.MODE_PRIVATE).edit().putBoolean(K_PENDING, pending).apply()
            updateAll(ctx)
        }
    }
}

/** Sendet „Verriegeln“ und wartet bis zu 2 Minuten auf die Rückmeldung des Autos. */
class LockWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val store = SecureStore(ctx)
        val http = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(40, TimeUnit.SECONDS).build()
        val auth = AuthRepository(store, http)
        val remote = RemoteControl(store, auth, http)
        try {
            if (!auth.isLoggedIn) {
                Notifier.remoteResult(ctx, "Nicht verriegelt", "Nicht bei Opel angemeldet – bitte die App öffnen.")
                return Result.success()
            }
            if (!remote.isActivated) {
                Notifier.remoteResult(ctx, "Nicht verriegelt", "Die Fernsteuerung ist noch nicht eingerichtet (App → Fernsteuerung).")
                return Result.success()
            }
            val prefs = ctx.getSharedPreferences("app", Context.MODE_PRIVATE)
            val vehicleId = prefs.getString("vehicle_id", null)
            val vin = prefs.getString("cache_vehicles", null)
                ?.let { runCatching { JSONObject(it) }.getOrNull() }
                .obj("_embedded").arr("vehicles").objects()
                .let { list -> list.firstOrNull { it.str("id") == vehicleId } ?: list.firstOrNull() }
                ?.str("vin")
            if (vin == null) {
                Notifier.remoteResult(ctx, "Nicht verriegelt", "Kein Fahrzeug bekannt – bitte einmal die App öffnen.")
                return Result.success()
            }
            val id = remote.send(vin, "/Doors", JSONObject().put("action", "lock"))
            val result = withTimeoutOrNull(120_000) { remote.updates.first { it.correlationId == id && it.final } }
            when {
                result == null -> Notifier.remoteResult(ctx, "Verriegeln gesendet", "Keine Rückmeldung vom Auto innerhalb von 2 Minuten.")
                result.success -> Notifier.remoteResult(ctx, "Auto verriegelt", result.label)
                else -> Notifier.remoteResult(ctx, "Verriegeln fehlgeschlagen", result.label)
            }
            return Result.success()
        } catch (e: AuthExpiredException) {
            Notifier.loginExpired(ctx)
            return Result.success()
        } catch (e: Exception) {
            Notifier.remoteResult(ctx, "Verriegeln fehlgeschlagen", e.message ?: e.toString())
            return Result.success()
        } finally {
            remote.disconnect()
            StatusWidget.setPending(ctx, false)
        }
    }
}
