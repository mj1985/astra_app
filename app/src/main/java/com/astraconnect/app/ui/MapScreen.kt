package com.astraconnect.app.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color as AColor
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.drawable.BitmapDrawable
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
import org.osmdroid.views.overlay.CopyrightOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon
import org.osmdroid.views.overlay.Polyline
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

/**
 * Ruhigere Kartenfarben: OSM-Kacheln entsättigt; im Dunkelmodus zusätzlich invertiert und
 * gedämpft, damit die Karte zum dunklen App-Hintergrund passt und Marker hervortreten.
 */
private object MapStyle {
    private fun matrix(dark: Boolean): ColorMatrix {
        val m = ColorMatrix().apply { setSaturation(if (dark) 0.12f else 0.30f) }
        val tone = if (dark) {
            // invertieren und auf ca. 14…218 von 255 stauchen
            val k = 0.80f; val t = 255f * k + 14f
            ColorMatrix(floatArrayOf(-k, 0f, 0f, 0f, t, 0f, -k, 0f, 0f, t + 1f, 0f, 0f, -k, 0f, t + 2f, 0f, 0f, 0f, 1f, 0f))
        } else {
            val k = 0.90f; val t = 22f
            ColorMatrix(floatArrayOf(k, 0f, 0f, 0f, t, 0f, k, 0f, 0f, t, 0f, 0f, k, 0f, t, 0f, 0f, 0f, 1f, 0f))
        }
        m.postConcat(tone)
        return m
    }

    val dark = ColorMatrixColorFilter(matrix(true))
    val light = ColorMatrixColorFilter(matrix(false))
}

/** Schlichte Punkt-Marker in der Akzentfarbe der App. */
class MapIcons(private val ctx: Context, private val accent: Int, private val ring: Int) {
    private fun dp(v: Float) = v * ctx.resources.displayMetrics.density

    private fun icon(sizeDp: Float, draw: Canvas.(c: Float) -> Unit): BitmapDrawable {
        val px = dp(sizeDp).toInt().coerceAtLeast(1)
        val bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        Canvas(bmp).draw(px / 2f)
        return BitmapDrawable(ctx.resources, bmp)
    }

    private fun fill(color: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }

    /** Fahrzeug: gefüllter Punkt mit Ring und weichem Hof. */
    val vehicle: BitmapDrawable by lazy {
        icon(30f) { c ->
            drawCircle(c, c, c, fill(AColor.argb(70, AColor.red(accent), AColor.green(accent), AColor.blue(accent))))
            drawCircle(c, c, dp(7.5f), fill(ring))
            drawCircle(c, c, dp(5.5f), fill(accent))
        }
    }

    /** Ziel bzw. Ende: gefüllter Punkt mit Ring. */
    val dot: BitmapDrawable by lazy {
        icon(16f) { c ->
            drawCircle(c, c, c, fill(ring))
            drawCircle(c, c, c - dp(2f), fill(accent))
        }
    }

    /** Gespeicherter Ort bzw. Start: hohler Ring. */
    val ringIcon: BitmapDrawable by lazy {
        icon(16f) { c ->
            drawCircle(c, c, c, fill(ring))
            drawCircle(c, c, c - dp(3.5f), Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = accent; style = Paint.Style.STROKE; strokeWidth = dp(2.5f)
            })
        }
    }
}

private fun MapView.marker(pos: GeoPoint, icon: BitmapDrawable, title: String? = null) {
    overlays += Marker(this).apply {
        position = pos
        this.icon = icon
        this.title = title
        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
        if (title == null) setInfoWindow(null)
    }
}

/** Kartenansicht mit Lebenszyklus-Anbindung; [update] wird bei jeder Neukomposition aufgerufen. */
@SuppressLint("ClickableViewAccessibility")
@Composable
private fun OsmMap(
    modifier: Modifier,
    interactive: Boolean = true,
    update: (MapView, MapIcons) -> Unit,
) {
    val ctx = LocalContext.current
    val dark = isSystemInDarkTheme()
    val accent = MaterialTheme.colorScheme.primary.toArgb()
    val ring = MaterialTheme.colorScheme.surface.toArgb()
    val icons = remember(accent, ring) { MapIcons(ctx, accent, ring) }
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
            m.overlayManager.tilesOverlay.setColorFilter(if (dark) MapStyle.dark else MapStyle.light)
            m.overlayManager.tilesOverlay.loadingBackgroundColor = ring
            m.overlayManager.tilesOverlay.loadingLineColor = ring
            m.overlays.clear()
            update(m, icons)
            // Quellenangabe (OSM-Lizenz)
            m.overlays += CopyrightOverlay(ctx).apply {
                setTextColor(if (dark) AColor.argb(150, 255, 255, 255) else AColor.argb(150, 0, 0, 0))
                setTextSize(9)
                setAlignRight(true)
            }
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
        OsmMap(Modifier.fillMaxSize()) { map, icons ->
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
            ui.places.forEach { p -> map.marker(p.pos.geo(), icons.ringIcon, p.name) }
            ui.status?.position?.let { map.marker(it.geo(), icons.vehicle, "Fahrzeug (letzter Standort)") }
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
        OsmMap(Modifier.fillMaxSize(), interactive = false) { map, icons ->
            val pts = listOfNotNull(a, b)
            if (a != null && b != null) {
                map.overlays += Polyline(map).apply {
                    setPoints(pts); outlinePaint.color = color; outlinePaint.strokeWidth = 6f
                }
            }
            a?.let { map.marker(it, icons.ringIcon) }
            b?.let { map.marker(it, icons.dot) }
            map.fit(pts)
        }
    }
}

/** Kleine, nicht bedienbare Karte mit dem Standort des Fahrzeugs. */
@Composable
fun PositionMiniMap(pos: LatLon, modifier: Modifier = Modifier, height: Dp = 170.dp) {
    Box(modifier.fillMaxWidth().height(height).clip(RoundedCornerShape(14.dp))) {
        OsmMap(Modifier.fillMaxSize(), interactive = false) { map, icons ->
            map.marker(pos.geo(), icons.vehicle)
            map.fit(listOf(pos.geo()))
        }
    }
}
