package com.astraconnect.app.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color as AColor
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.astraconnect.app.AppViewModel
import com.astraconnect.app.data.LatLon
import com.astraconnect.app.data.PlaceBook
import com.astraconnect.app.data.Trip
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.views.overlay.TilesOverlay
import java.io.File
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.math.sqrt

private object MapSupport {
    @Volatile private var ready = false

    fun init(ctx: Context) {
        if (ready) return
        Configuration.getInstance().apply {
            load(ctx, ctx.getSharedPreferences("osmdroid", Context.MODE_PRIVATE))
            // OSM-Nutzungsrichtlinie: eindeutiger User-Agent, Kacheln im App-Cache
            userAgentValue = ctx.packageName
            osmdroidBasePath = File(ctx.cacheDir, "osmdroid")
            osmdroidTileCache = File(ctx.cacheDir, "osmdroid/tiles")
        }
        ready = true
    }
}

private fun LatLon.geo() = GeoPoint(lat, lon)

/** Kartenansicht mit Lebenszyklus-Anbindung; [update] wird bei jeder Neukomposition aufgerufen. */
@SuppressLint("ClickableViewAccessibility")
@Composable
private fun OsmMap(
    modifier: Modifier,
    interactive: Boolean = true,
    update: (MapView) -> Unit,
) {
    val ctx = LocalContext.current
    val dark = isSystemInDarkTheme()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val map = remember {
        MapSupport.init(ctx)
        MapView(ctx).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(interactive)
            zoomController.setVisibility(org.osmdroid.views.CustomZoomButtonsController.Visibility.NEVER)
            controller.setZoom(11.0)
            controller.setCenter(GeoPoint(53.92, 10.82))
            if (!interactive) setOnTouchListener { _, _ -> true }
        }
    }
    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_RESUME -> map.onResume()
                Lifecycle.Event.ON_PAUSE -> map.onPause()
                else -> {}
            }
        }
        lifecycle.addObserver(obs)
        map.onResume()
        onDispose {
            lifecycle.removeObserver(obs)
            map.onPause()
            map.onDetach()
        }
    }
    AndroidView(
        factory = { map },
        modifier = modifier,
        update = { m ->
            m.overlayManager.tilesOverlay.setColorFilter(if (dark) TilesOverlay.INVERT_COLORS else null)
            m.overlays.clear()
            update(m)
            m.invalidate()
        },
    )
}

private fun MapView.fit(points: List<GeoPoint>) {
    if (points.isEmpty()) return
    val run = {
        if (points.size == 1) {
            controller.setZoom(15.0); controller.setCenter(points.first())
        } else {
            zoomToBoundingBox(BoundingBox.fromGeoPointsSafe(points).increaseByScale(1.15f), false, 40)
        }
    }
    if (width > 0) run() else addOnFirstLayoutListener { _, _, _, _, _ -> run() }
}

@Composable
fun MapScreen(vm: AppViewModel) {
    val ui = vm.ui
    var mode by rememberSaveable { mutableStateOf(0) }       // 0 = Fahrten, 1 = häufige Orte
    var period by rememberSaveable { mutableStateOf(30) }    // Tage, 0 = alles
    val electric = ChartColors.electric.toArgb()
    val fuel = ChartColors.fuel.toArgb()
    val since = if (period == 0) null else Instant.now().minus(period.toLong(), ChronoUnit.DAYS)
    val trips = ui.trips.filter { since == null || (it.startedAt?.isAfter(since) == true) }
    val spots = remember(trips) { PlaceBook.hotspots(trips) }

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = mode == 0, onClick = { mode = 0 }, label = { Text("Fahrten") })
                FilterChip(selected = mode == 1, onClick = { mode = 1 }, label = { Text("Häufige Orte") })
                listOf(7 to "7 Tage", 30 to "30 Tage", 365 to "12 Monate", 0 to "Alles").forEach { (d, l) ->
                    FilterChip(selected = period == d, onClick = { period = d }, label = { Text(l) })
                }
            }
            Text(
                if (mode == 0) "${trips.size} Fahrten · Linie = Start → Ziel (die API liefert keinen Streckenverlauf). " +
                    "Blau = rein elektrisch, Orange = mit Benzin."
                else "${spots.size} Orte · Kreisgröße = Anzahl der Besuche (Start + Ziel).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        OsmMap(Modifier.fillMaxSize()) { map ->
            val all = mutableListOf<GeoPoint>()
            if (mode == 0) {
                trips.forEach { t ->
                    val a = t.startPos?.geo() ?: return@forEach
                    val b = t.endPos?.geo() ?: return@forEach
                    map.overlays += Polyline(map).apply {
                        setPoints(listOf(a, b))
                        outlinePaint.color = if (t.fuelLiters?.let { it > 0.05 } == true) fuel else electric
                        outlinePaint.strokeWidth = 5f
                        outlinePaint.alpha = 170
                        title = "${t.startedAt.dateTime()} · ${t.distanceKm.km(1)}"
                    }
                    all += a; all += b
                }
            } else {
                val maxVisits = spots.maxOfOrNull { it.visits }?.toDouble() ?: 1.0
                spots.forEach { s ->
                    val r = 60.0 + 340.0 * sqrt(s.visits / maxVisits)
                    map.overlays += Polygon(map).apply {
                        points = Polygon.pointsAsCircle(s.pos.geo(), r)
                        fillPaint.color = AColor.argb(90, AColor.red(electric), AColor.green(electric), AColor.blue(electric))
                        outlinePaint.color = electric
                        outlinePaint.strokeWidth = 2f
                        title = "${s.visits} Besuche" + (s.address?.let { "\n$it" } ?: "")
                    }
                    all += s.pos.geo()
                }
            }
            ui.places.forEach { p ->
                map.overlays += Marker(map).apply {
                    position = p.pos.geo()
                    title = p.name
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                }
            }
            map.fit(all)
        }
    }
}

/** Kleine, nicht bedienbare Karte mit Start- und Zielpunkt einer Fahrt. */
@Composable
fun TripMiniMap(t: Trip, height: Dp = 180.dp) {
    val a = t.startPos?.geo()
    val b = t.endPos?.geo()
    if (a == null && b == null) return
    val color = (if (t.fuelLiters?.let { it > 0.05 } == true) ChartColors.fuel else ChartColors.electric).toArgb()
    Box(Modifier.fillMaxWidth().height(height).clip(RoundedCornerShape(14.dp))) {
        OsmMap(Modifier.fillMaxSize(), interactive = false) { map ->
            val pts = listOfNotNull(a, b)
            if (a != null && b != null) {
                map.overlays += Polyline(map).apply {
                    setPoints(pts); outlinePaint.color = color; outlinePaint.strokeWidth = 6f
                }
            }
            a?.let { map.overlays += Marker(map).apply { position = it; title = "Start"; setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM) } }
            b?.let { map.overlays += Marker(map).apply { position = it; title = "Ziel"; setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM) } }
            map.fit(pts)
        }
    }
}
