package com.astraconnect.app.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.net.Uri
import android.view.View
import android.widget.RemoteViews
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.astraconnect.app.R
import com.astraconnect.app.data.AuthExpiredException
import com.astraconnect.app.data.AuthRepository
import com.astraconnect.app.data.Garage
import com.astraconnect.app.data.Notifier
import com.astraconnect.app.data.RemoteCommand
import com.astraconnect.app.data.RemoteControl
import com.astraconnect.app.data.SecureStore
import com.astraconnect.app.data.StellantisApi
import com.astraconnect.app.data.VehicleSettings
import com.astraconnect.app.data.VehicleStatus
import com.astraconnect.app.ui.pct
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.roundToInt

/** Befehle, die das Steuer-Widget senden kann. Entriegeln gibt es bewusst nicht. */
enum class WidgetCommand(
    /** Taste im Widget – Klima an/aus teilen sich eine Taste. */
    val key: String,
    val label: String,
    /** Titel der Benachrichtigung bei Erfolg; null = keine (Hupe/Licht hört bzw. sieht man). */
    val doneTitle: String?,
) {
    CLIMATE_ON("climate", "Klimatisierung starten", "Klimatisierung gestartet"),
    CLIMATE_OFF("climate", "Klimatisierung stoppen", "Klimatisierung gestoppt"),
    HORN("horn", "Hupe", null),
    LIGHTS("light", "Lichtsignal", null),
    LOCK("lock", "Verriegeln", "Auto verriegelt"),
    CHARGE_START("charge", "Laden starten", "Laden gestartet"),
    /** Pausieren = verzögertes Laden ab der im Auto programmierten Startzeit. */
    CHARGE_STOP("charge", "Laden pausieren", "Laden pausiert");

    fun command(status: JSONObject?): RemoteCommand = when (this) {
        CLIMATE_ON -> RemoteCommand.preconditioning(true, status)
        CLIMATE_OFF -> RemoteCommand.preconditioning(false, status)
        HORN -> RemoteCommand.horn()
        LIGHTS -> RemoteCommand.lights()
        LOCK -> RemoteCommand.lock()
        CHARGE_START, CHARGE_STOP -> RemoteCommand.charging(
            this == CHARGE_START,
            status?.let { runCatching { VehicleStatus.parse(it) }.getOrNull() }?.charging?.nextDelayedTime,
        )
    }

    /** Taste, die diesen Befehl an das Fahrzeug [vehicleId] sendet (Broadcast an das Widget [widget]). */
    fun pendingIntent(ctx: Context, widget: Class<out BaseWidget>, requestBase: Int, vehicleId: String): PendingIntent {
        val intent = Intent(ctx, widget).setAction(ControlWidget.ACTION_COMMAND)
            // Daten-URI je Fahrzeug und Befehl: Extras allein unterscheiden PendingIntents nicht –
            // sonst würde die Taste eines Widgets für Auto A den Befehl an Auto B schicken
            .setData(Uri.parse("astra://befehl/${Uri.encode(vehicleId)}/$name"))
            .putExtra(ControlWidget.EXTRA_CMD, name)
            .putExtra(ControlWidget.EXTRA_VEHICLE, vehicleId)
        return PendingIntent.getBroadcast(ctx, requestBase + ordinal, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    /** Merkschlüssel „läuft gerade“ – je Fahrzeug und Taste. */
    fun pendingKey(vehicleId: String) = "${Garage.safe(vehicleId)}/$key"
}

/**
 * Steuer-Widget (5×2): links das angeschnittene Fahrzeugbild mit Akku- und Tankstand,
 * rechts Klimatisierung, Hupe, Licht, Verriegeln.
 */
class ControlWidget : BaseWidget() {

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        handleCommand(context, intent)
    }

    companion object {
        const val ACTION_COMMAND = "com.astraconnect.app.WIDGET_COMMAND"
        const val EXTRA_CMD = "cmd"
        const val EXTRA_VEHICLE = "vehicle"

        /** Tastendruck aus einem Widget: Befehl vormerken (Taste zeigt „Sende …“) und im Hintergrund senden. */
        internal fun handleCommand(context: Context, intent: Intent) {
            if (intent.action != ACTION_COMMAND) return
            val cmd = intent.getStringExtra(EXTRA_CMD)?.let { runCatching { WidgetCommand.valueOf(it) }.getOrNull() } ?: return
            val vehicleId = intent.getStringExtra(EXTRA_VEHICLE) ?: Garage.selectedId(context)
            if (!PendingCommands.add(context, cmd.pendingKey(vehicleId))) return // läuft schon
            Widgets.updateAll(context)
            WorkManager.getInstance(context).enqueueUniqueWork(
                "widget_command", ExistingWorkPolicy.APPEND_OR_REPLACE,
                OneTimeWorkRequestBuilder<WidgetCommandWorker>()
                    .setInputData(workDataOf(EXTRA_CMD to cmd.name, EXTRA_VEHICLE to vehicleId))
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .build(),
            )
        }

        /** Anteil der Breite für das Bild – muss zu den layout_weight-Werten in widget_control.xml passen. */
        private const val CAR_SHARE = 1.25 / (1.25 + 1.0)
        /** Senkrechter Platz, der nicht fürs Bild bleibt: Innenabstand und Prozentzeile. */
        private const val CAR_RESERVED_DP = 16 + 22

        /**
         * Seitenverhältnis der Bildfläche aus der Widget-Größe (Hochformat: minWidth × maxHeight).
         * Bei mehreren Steuer-Widgets bestimmt das erste; ohne Angabe gilt ein übliches 5×2-Maß.
         */
        private fun carBoxAspect(ctx: Context): Double {
            val mgr = AppWidgetManager.getInstance(ctx)
            val id = mgr.getAppWidgetIds(ComponentName(ctx, ControlWidget::class.java)).firstOrNull()
            val o = id?.let { mgr.getAppWidgetOptions(it) }
            val w = o?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH)?.takeIf { it > 0 } ?: 350
            val h = o?.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT)?.takeIf { it > 0 } ?: 180
            return (w * CAR_SHARE) / (h - CAR_RESERVED_DP).coerceAtLeast(40)
        }

        fun render(src: WidgetSource): RemoteViews {
            val ctx = src.ctx
            val s = src.status
            val v = src.views(R.layout.widget_control)
            val pending = PendingCommands.get(src.prefs)

            fun button(btn: Int, icon: Int, label: Int, text: String, cmd: WidgetCommand, active: Boolean, tint: Int? = null) {
                val busy = cmd.pendingKey(src.vehicleId) in pending
                v.setTextViewText(label, if (busy) "Sende …" else text)
                v.setInt(icon, "setBackgroundResource", if (active) R.drawable.widget_round_bg_active else R.drawable.widget_round_bg)
                v.setInt(icon, "setColorFilter", if (active) COLOR_DARK else tint ?: Color.WHITE)
                v.setInt(icon, "setImageAlpha", if (busy) 110 else 255)
                v.setOnClickPendingIntent(btn, cmd.pendingIntent(ctx, ControlWidget::class.java, 100, src.vehicleId))
            }

            val climateOn = s?.preconditioning.equals("Enabled", true)
            button(R.id.w_btn_climate, R.id.w_icon_climate, R.id.w_label_climate,
                if (climateOn) "Klima an" else "Klima",
                if (climateOn) WidgetCommand.CLIMATE_OFF else WidgetCommand.CLIMATE_ON, active = climateOn)
            button(R.id.w_btn_horn, R.id.w_icon_horn, R.id.w_label_horn, "Hupe", WidgetCommand.HORN, false)
            button(R.id.w_btn_light, R.id.w_icon_light, R.id.w_label_light, "Licht", WidgetCommand.LIGHTS, false)
            val locked = s?.isLocked == true
            button(R.id.w_btn_lock, R.id.w_icon_lock, R.id.w_label_lock,
                if (locked) "Verriegelt" else "Verriegeln", WidgetCommand.LOCK, false, tint = if (locked) COLOR_OK else null)

            CarImage.ensure(ctx, src.vehicleId)
            val car = CarImage.load(ctx, src.vehicleId, carBoxAspect(ctx))
            if (car != null) v.setImageViewBitmap(R.id.w_car, car)
            val pt = src.profile.powertrain
            v.setTextViewText(R.id.w_car_soc, s?.batteryPercent.pct())
            v.setTextViewText(R.id.w_car_fuel, s?.fuelPercent.pct())
            v.setViewVisibility(R.id.w_car_soc_group, if (pt.hasBattery) View.VISIBLE else View.GONE)
            v.setViewVisibility(R.id.w_car_fuel_group, if (pt.hasFuel) View.VISIBLE else View.GONE)
            // Ohne Antriebsbatterie keine Vorklimatisierung aus der Ferne
            v.setViewVisibility(R.id.w_btn_climate, if (pt.hasRemoteClimate) View.VISIBLE else View.GONE)
            v.setOnClickPendingIntent(R.id.w_car_box, src.openApp())
            return v
        }
    }
}

/** Gerade gesendete Befehle (Taste zeigt „Sende …“). Einträge verfallen nach 10 Minuten. */
internal object PendingCommands {
    private const val K = "widget_cmd_pending"
    private const val MAX_AGE_MS = 10 * 60_000L

    private fun entries(prefs: SharedPreferences): Map<String, Long> {
        val now = System.currentTimeMillis()
        return (prefs.getStringSet(K, emptySet()) ?: emptySet()).mapNotNull { e ->
            val key = e.substringBefore('@')
            val at = e.substringAfter('@', "").toLongOrNull() ?: return@mapNotNull null
            (key to at).takeIf { now - at < MAX_AGE_MS }
        }.toMap()
    }

    fun get(prefs: SharedPreferences): Set<String> = entries(prefs).keys

    /** false, wenn derselbe Befehl ([WidgetCommand.pendingKey]) schon unterwegs ist. */
    @Synchronized
    fun add(ctx: Context, key: String): Boolean {
        val prefs = Garage.appPrefs(ctx)
        val current = entries(prefs)
        if (key in current) return false
        save(prefs, current + (key to System.currentTimeMillis()))
        return true
    }

    @Synchronized
    fun remove(ctx: Context, key: String) {
        val prefs = Garage.appPrefs(ctx)
        save(prefs, entries(prefs) - key)
    }

    private fun save(prefs: SharedPreferences, m: Map<String, Long>) {
        prefs.edit().putStringSet(K, m.map { (k, at) -> "$k@$at" }.toSet()).commit()
    }
}

/** Sendet einen Widget-Befehl und wartet bis zu 2 Minuten auf die Rückmeldung des Autos. */
class WidgetCommandWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val cmd = inputData.getString(ControlWidget.EXTRA_CMD)
            ?.let { runCatching { WidgetCommand.valueOf(it) }.getOrNull() } ?: return Result.success()
        val vehicleId = inputData.getString(ControlWidget.EXTRA_VEHICLE) ?: Garage.selectedId(ctx)
        val store = SecureStore(ctx)
        val http = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(40, TimeUnit.SECONDS).build()
        val auth = AuthRepository(store, http)
        val remote = RemoteControl(store, auth, http)
        val failed = "${cmd.label} fehlgeschlagen"
        fun report(title: String, text: String) = Notifier.remoteResult(ctx, vehicleId, title, text)
        try {
            if (!auth.isLoggedIn) {
                report(failed, "Nicht bei Opel angemeldet – bitte die App öffnen.")
                return Result.success()
            }
            if (!remote.isActivated) {
                report(failed, "Die Fernsteuerung ist noch nicht eingerichtet (App → Fernsteuerung).")
                return Result.success()
            }
            val vehicle = Garage.vehicle(ctx, vehicleId)
            val vin = vehicle?.vin?.takeIf { it.isNotBlank() }
            if (vehicle == null || vin == null) {
                report(failed, "Kein Fahrzeug bekannt – bitte einmal die App öffnen.")
                return Result.success()
            }
            val settings = VehicleSettings(ctx, vehicle.id)
            val c = cmd.command(settings.cachedStatus)
            val id = remote.send(vin, c.service, c.params)
            val result = withTimeoutOrNull(120_000) { remote.updates.first { it.correlationId == id && it.final } }
            when {
                result == null -> report("${cmd.label} gesendet", "Keine Rückmeldung vom Auto innerhalb von 2 Minuten.")
                result.success -> {
                    cmd.doneTitle?.let { report(it, result.label) }
                    // Neuen Stand holen, damit Klima-/Schloss-/Lade-Taste stimmen
                    if (cmd != WidgetCommand.HORN && cmd != WidgetCommand.LIGHTS) {
                        runCatching { settings.cacheStatus(StellantisApi(auth, http).status(vehicle.id)) }
                    }
                }
                else -> report(failed, result.label)
            }
            return Result.success()
        } catch (e: AuthExpiredException) {
            Notifier.loginExpired(ctx)
            return Result.success()
        } catch (e: Exception) {
            report(failed, e.message ?: e.toString())
            return Result.success()
        } finally {
            remote.disconnect()
            PendingCommands.remove(ctx, cmd.pendingKey(vehicleId))
            Widgets.updateAll(ctx)
        }
    }
}

/**
 * Ansicht des Fahrzeugbilds. Opel liefert die Ansicht über den Parameter `view` der Bild-URL:
 * 001 = Seitenansicht (so kommt die URL aus der API), 002 = Schrägansicht von vorn.
 */
internal enum class CarView(val view: String?, private val prefix: String) {
    SIDE(null, "widget_car"),
    FRONT("002", "widget_car_002");

    val fileName get() = "$prefix.png"
    val keyUrl get() = "${prefix}_url"
    val keyTried get() = "${prefix}_tried"
    val workName get() = "${prefix}_image"

    /** Bild-URL dieser Ansicht aus der URL der API. */
    fun url(base: String): String = when {
        view == null -> base
        VIEW_PARAM.containsMatchIn(base) -> VIEW_PARAM.replace(base) { "${it.groupValues[1]}$view" }
        else -> base + (if ('?' in base) "&" else "?") + "view=$view"
    }

    private companion object {
        val VIEW_PARAM = Regex("([?&]view=)[^&]*")
    }
}

/**
 * Fahrzeugbilder für die Widgets: einmal von Opel laden, Hintergrund freistellen, Ränder abschneiden
 * und als PNG ablegen. Die Seitenansicht zeigt die Front nach rechts – fürs Steuer-Widget wird links
 * das Heck so weit abgeschnitten, dass das Bild die linke Widget-Hälfte möglichst ausfüllt.
 */
internal object CarImage {
    /** Vom Auto bleibt mindestens bzw. höchstens dieser Anteil sichtbar (Front immer ganz). */
    private const val MIN_KEEP = 0.55
    private const val MAX_KEEP = 0.88
    private const val MAX_HEIGHT = 480

    /** Bilder liegen beim Fahrzeug ([Garage.dir]), gemerkte URLs in dessen Einstellungen. */
    private fun file(ctx: Context, vehicleId: String, cv: CarView) = File(Garage.dir(ctx, vehicleId), cv.fileName)

    /** Lädt das Bild des Fahrzeugs im Hintergrund nach, wenn es fehlt oder sich die URL geändert hat. */
    fun ensure(ctx: Context, vehicleId: String, cv: CarView = CarView.SIDE) {
        val url = Garage.vehicle(ctx, vehicleId)?.pictureUrl?.let(cv::url) ?: return
        val prefs = Garage.prefs(ctx, vehicleId)
        if (prefs.getString(cv.keyUrl, null) == url && file(ctx, vehicleId, cv).exists()) return
        // Fehlversuche höchstens alle 6 Stunden wiederholen
        val tried = prefs.getString(cv.keyTried, null)
        if (tried != null && tried.substringBefore('|') == url &&
            System.currentTimeMillis() - (tried.substringAfter('|').toLongOrNull() ?: 0) < 6 * 3600_000L
        ) return
        WorkManager.getInstance(ctx).enqueueUniqueWork(
            "${cv.workName}_${Garage.safe(vehicleId)}", ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<CarImageWorker>()
                .setInputData(workDataOf(CarImageWorker.KEY_VIEW to cv.name, CarImageWorker.KEY_VEHICLE to vehicleId))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build(),
        )
    }

    /** Ganzes Bild der Ansicht [cv], verkleinert auf höchstens [maxHeight] Pixel Höhe. */
    fun loadWhole(ctx: Context, vehicleId: String, cv: CarView, maxHeight: Int = 360): Bitmap? = runCatching {
        val src = BitmapFactory.decodeFile(file(ctx, vehicleId, cv).path) ?: return null
        if (src.height <= maxHeight) return src
        val scale = maxHeight.toFloat() / src.height
        Bitmap.createScaledBitmap(src, (src.width * scale).roundToInt().coerceAtLeast(1), maxHeight, true)
    }.getOrNull()

    /** Seitenansicht passend zum Seitenverhältnis [boxAspect] (Breite/Höhe) der Bildfläche im Widget. */
    fun load(ctx: Context, vehicleId: String, boxAspect: Double): Bitmap? = runCatching {
        val src = BitmapFactory.decodeFile(file(ctx, vehicleId, CarView.SIDE).path) ?: return null
        val keepFrac = keepFraction(src.width, src.height, boxAspect)
        val keep = (src.width * keepFrac).roundToInt().coerceIn(1, src.width)
        val scale = minOf(1f, MAX_HEIGHT.toFloat() / src.height)
        // Links (Heck) abschneiden – die Front zeigt nach rechts zu den Tasten
        val m = Matrix().apply { postScale(scale, scale) }
        Bitmap.createBitmap(src, src.width - keep, 0, keep, src.height, m, true)
    }.getOrNull()

    /** Sichtbarer Anteil des Autos, damit das Bild die Fläche [boxAspect] füllt. */
    internal fun keepFraction(width: Int, height: Int, boxAspect: Double): Double {
        if (width <= 0 || height <= 0 || boxAspect <= 0) return MAX_KEEP
        return (boxAspect * height / width).coerceIn(MIN_KEEP, MAX_KEEP)
    }

    suspend fun download(ctx: Context, vehicleId: String, cv: CarView) = withContext(Dispatchers.IO) {
        val prefs = Garage.prefs(ctx, vehicleId)
        val url = Garage.vehicle(ctx, vehicleId)?.pictureUrl?.let(cv::url) ?: return@withContext
        prefs.edit().putString(cv.keyTried, "$url|${System.currentTimeMillis()}").apply()
        val http = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(40, TimeUnit.SECONDS).build()
        val bytes = http.newCall(Request.Builder().url(url).build()).execute().use { r ->
            if (!r.isSuccessful) error("HTTP ${r.code}")
            r.body?.bytes() ?: error("leer")
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= 900) sample *= 2
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: error("Bild nicht lesbar")
        val clean = cleanup(decoded)
        val target = file(ctx, vehicleId, cv)
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, "${cv.fileName}.tmp")
        tmp.outputStream().use { clean.compress(Bitmap.CompressFormat.PNG, 100, it) }
        if (!tmp.renameTo(target)) { target.delete(); tmp.renameTo(target) }
        prefs.edit().putString(cv.keyUrl, url).apply()
    }

    /** Einfarbigen Hintergrund (vom Rand aus) transparent machen und leere Ränder abschneiden. */
    private fun cleanup(src: Bitmap): Bitmap {
        val w = src.width
        val h = src.height
        val px = IntArray(w * h)
        src.copy(Bitmap.Config.ARGB_8888, false).getPixels(px, 0, w, 0, 0, w, h)

        val bg = px[0]
        if (Color.alpha(bg) > 200) {
            fun near(c: Int) = Color.alpha(c) > 200 &&
                abs(Color.red(c) - Color.red(bg)) + abs(Color.green(c) - Color.green(bg)) + abs(Color.blue(c) - Color.blue(bg)) <= 36
            val seen = BooleanArray(w * h)
            val queue = IntArray(w * h)
            var head = 0
            var tail = 0
            fun push(i: Int) {
                if (!seen[i] && near(px[i])) { seen[i] = true; queue[tail++] = i }
            }
            for (x in 0 until w) { push(x); push((h - 1) * w + x) }
            for (y in 0 until h) { push(y * w); push(y * w + w - 1) }
            while (head < tail) {
                val i = queue[head++]
                px[i] = Color.TRANSPARENT
                val x = i % w
                if (x > 0) push(i - 1)
                if (x < w - 1) push(i + 1)
                if (i >= w) push(i - w)
                if (i < w * (h - 1)) push(i + w)
            }
        }

        var minX = w; var minY = h; var maxX = -1; var maxY = -1
        for (y in 0 until h) for (x in 0 until w) {
            if (Color.alpha(px[y * w + x]) > 16) {
                if (x < minX) minX = x
                if (x > maxX) maxX = x
                if (y < minY) minY = y
                if (y > maxY) maxY = y
            }
        }
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(px, 0, w, 0, 0, w, h)
        return if (maxX < 0) out else Bitmap.createBitmap(out, minX, minY, maxX - minX + 1, maxY - minY + 1)
    }
}

class CarImageWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val cv = inputData.getString(KEY_VIEW)?.let { runCatching { CarView.valueOf(it) }.getOrNull() } ?: CarView.SIDE
        val vehicleId = inputData.getString(KEY_VEHICLE) ?: Garage.selectedId(applicationContext)
        runCatching { CarImage.download(applicationContext, vehicleId, cv) }
            .onFailure { android.util.Log.w("CarImage", "Fahrzeugbild ($cv) nicht geladen", it) }
        Widgets.updateAll(applicationContext)
        return Result.success()
    }

    companion object {
        const val KEY_VIEW = "view"
        const val KEY_VEHICLE = "vehicle"
    }
}
