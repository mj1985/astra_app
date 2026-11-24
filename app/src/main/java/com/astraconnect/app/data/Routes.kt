package com.astraconnect.app.data

import android.content.Context
import org.json.JSONArray
import java.io.File
import java.time.Duration
import java.time.Instant

/**
 * Grober Streckenverlauf einer Fahrt aus den Standorten, die die App während der Fahrt aufgezeichnet hat
 * (Opel liefert keine Wegpunkte – der Endpunkt dafür ist veraltet und defekt).
 */
data class TripRoute(val points: List<LatLon>) {
    val available: Boolean get() = points.size >= 2

    companion object {
        /** Spielraum um Abfahrt/Ankunft, in dem aufgezeichnete Standorte noch zur Fahrt zählen. */
        val MARGIN: Duration = Duration.ofMinutes(1)

        /**
         * Start → Punkte während der Fahrt → Ziel. null, wenn kein Punkt in die Fahrt fällt
         * (dann bleibt es bei der geraden Linie).
         */
        fun fromTrack(t: Trip, track: List<TrackPoint>): TripRoute? {
            val inner = pointsOf(t, track).map { it.pos }
            if (inner.isEmpty()) return null
            val all = listOfNotNull(t.startPos) + inner + listOfNotNull(t.endPos)
            val dedup = all.filterIndexed { i, p -> i == 0 || p != all[i - 1] }
            return TripRoute(dedup).takeIf { it.available }
        }

        /** Aufgezeichnete Standorte, die zeitlich in die Fahrt fallen (Abfahrt/Ankunft ± [MARGIN]), nach Zeit sortiert. */
        fun pointsOf(t: Trip, track: List<TrackPoint>): List<TrackPoint> {
            val from = t.startedAt?.minus(MARGIN) ?: return emptyList()
            val to = t.stoppedAt?.plus(MARGIN) ?: return emptyList()
            return track.filter { !it.at.isBefore(from) && !it.at.isAfter(to) }.sortedBy { it.at }
        }
    }
}

/** Ein vom Auto gemeldeter Standort. */
data class TrackPoint(val at: Instant, val pos: LatLon)

/**
 * Standorte aus jedem Statusabruf (Datei `positionen.json`). Während der Fahrt fragt die App etwa alle
 * 2 Minuten ab ([TripFollow]) – daraus entsteht ein grober Streckenverlauf.
 */
class TrackLog(private val file: File) {

    private var cache: MutableList<TrackPoint>? = null

    @Synchronized
    private fun all(): MutableList<TrackPoint> = cache ?: run {
        val arr = if (file.exists()) runCatching { JSONArray(file.readText()) }.getOrElse { JSONArray() } else JSONArray()
        (0 until arr.length()).mapNotNullTo(mutableListOf()) { i ->
            val a = arr.optJSONArray(i) ?: return@mapNotNullTo null
            TrackPoint(Instant.ofEpochSecond(a.optLong(0)), LatLon(a.optDouble(1), a.optDouble(2)))
        }.also { cache = it }
    }

    /** Standort aus dem Status speichern (gleicher Zeitpunkt nur einmal). Liefert true, wenn neu. */
    @Synchronized
    fun record(status: VehicleStatus, now: Instant = Instant.now()): Boolean {
        val at = status.positionAt ?: return false
        val pos = status.position ?: return false
        val list = all()
        if (list.any { it.at == at }) return false
        list += TrackPoint(at, pos)
        list.sortBy { it.at }
        list.removeAll { Duration.between(it.at, now) > KEEP }
        save(list)
        return true
    }

    @Synchronized
    fun between(from: Instant, to: Instant): List<TrackPoint> = all().filter { !it.at.isBefore(from) && !it.at.isAfter(to) }

    @Synchronized
    fun points(): List<TrackPoint> = all().toList()

    private fun save(list: List<TrackPoint>) {
        val arr = JSONArray()
        list.forEach { p ->
            arr.put(JSONArray().put(p.at.epochSecond).put(Math.round(p.pos.lat * 100_000) / 100_000.0).put(Math.round(p.pos.lon * 100_000) / 100_000.0))
        }
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(arr.toString())
        if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
    }

    private companion object {
        /** Ältere Standorte werden verworfen (Datei bleibt klein, Datenschutz). */
        val KEEP: Duration = Duration.ofDays(400)
    }
}

/**
 * Dichtere Abfrage während der Fahrt: Meldet der Status „fährt“ (in Bewegung oder Zündung an) und ist er
 * frisch, wird in 2 Minuten erneut abgefragt – so lange, bis das Auto steht. Nur Abrufe beim Opel-Server,
 * das Auto wird dabei nicht geweckt.
 */
object TripFollow {
    private const val WORK = "trip_follow"
    val INTERVAL: Duration = Duration.ofMinutes(2)
    /** Ältere Daten gelten nicht mehr als „fährt gerade“ (verhindert endloses Nachfragen). */
    val FRESH: Duration = Duration.ofMinutes(10)

    fun isDriving(s: VehicleStatus, now: Instant = Instant.now()): Boolean {
        val fresh = s.updatedAt?.let { Duration.between(it, now) <= FRESH } == true
        val driving = s.moving == true || s.ignition.equals("StartUp", true) || s.ignition.equals("Start", true)
        return fresh && driving
    }

    /** Nach jedem Statusabruf aufrufen; plant die nächste Abfrage, solange gefahren wird. */
    fun onStatus(ctx: Context, s: VehicleStatus) {
        val prefs = ctx.getSharedPreferences("app", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("bg_enabled", true) || !isDriving(s)) return
        androidx.work.WorkManager.getInstance(ctx).enqueueUniqueWork(
            WORK, androidx.work.ExistingWorkPolicy.REPLACE,
            androidx.work.OneTimeWorkRequestBuilder<SnapshotWorker>()
                .setInitialDelay(INTERVAL.seconds, java.util.concurrent.TimeUnit.SECONDS)
                .setConstraints(androidx.work.Constraints.Builder().setRequiredNetworkType(androidx.work.NetworkType.CONNECTED).build())
                .build(),
        )
    }
}
