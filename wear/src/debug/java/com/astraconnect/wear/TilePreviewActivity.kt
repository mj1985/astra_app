package com.astraconnect.wear

import android.app.Activity
import android.graphics.Color
import android.graphics.Outline
import android.os.Bundle
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ScrollView
import androidx.core.content.ContextCompat
import androidx.wear.protolayout.DeviceParametersBuilders
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.LayoutElementBuilders.LayoutElement
import androidx.wear.tiles.renderer.TileRenderer
import com.astraconnect.shared.WearContract
import com.astraconnect.shared.WearStatus

/**
 * Nur im Debug-Build: zeichnet das Tile mit Beispieldaten auf runden Flächen (192 dp wie kleine Uhren),
 * um das Layout ohne Uhr zu prüfen – auch auf einem Handy-Emulator:
 * adb shell am start -n com.astraconnect.app.debug/com.astraconnect.wear.TilePreviewActivity
 */
class TilePreviewActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val sizeDp = intent.getIntExtra("size", 192)
        val density = resources.displayMetrics.density
        val device = DeviceParametersBuilders.DeviceParameters.Builder()
            .setScreenWidthDp(sizeDp)
            .setScreenHeightDp(sizeDp)
            .setScreenDensity(density)
            .setScreenShape(DeviceParametersBuilders.SCREEN_SHAPE_ROUND)
            .setDevicePlatform(DeviceParametersBuilders.DEVICE_PLATFORM_WEAR_OS)
            .build()

        // Ein Fahrzeug im Konto → ohne Namen; die Werte entsprechen einem echten Stand auf der Pixel Watch 1
        val phev = WearStatus(
            vehicleId = "demo", vehicleName = "Opel Astra L Hybrid", hasBattery = true, hasClimate = true,
            batteryPercent = 73, fuelPercent = 64, rangeText = "20 km el. · 540 km", chargeText = "Lädt bis 19:32",
            charging = true, locked = true, climateOn = false, updatedAt = System.currentTimeMillis() - 12 * 60_000,
            sending = emptySet(), vehicleCount = 1,
        )
        val two = phev.copy(vehicleCount = 2)
        val layouts = TileLayouts(this)
        val samples: List<LayoutElement> = listOf(
            layouts.main(device, phev, TileUi()),
            layouts.main(device, two, TileUi()),
            layouts.main(device, phev.copy(charging = false, climateOn = true, locked = false, batteryPercent = 23), TileUi()),
            layouts.main(device, two.copy(charging = false), TileUi(sending = setOf(WearContract.KEY_LOCK))),
            layouts.confirm(device, phev, WearContract.CLIMATE_ON),
            layouts.confirm(device, two, WearContract.LOCK),
            layouts.main(
                device,
                phev.copy(hasBattery = false, hasClimate = false, batteryPercent = null, charging = false, rangeText = "540 km", chargeText = ""),
                TileUi(error = "Handy offline"),
            ),
            layouts.noData(device, null),
        )

        val grid = GridLayout(this).apply {
            columnCount = 2
            setBackgroundColor(Color.DKGRAY)
        }
        val px = (sizeDp * density).toInt()
        val margin = (6 * density).toInt()
        for (element in samples) {
            // Ein Renderer zeichnet immer nur ein Layout
            val renderer = TileRenderer(this, ContextCompat.getMainExecutor(this)) { }
            val frame = FrameLayout(this).apply {
                setBackgroundColor(Color.BLACK)
                outlineProvider = object : ViewOutlineProvider() {
                    override fun getOutline(view: View, outline: Outline) = outline.setOval(0, 0, view.width, view.height)
                }
                clipToOutline = true
                layoutParams = GridLayout.LayoutParams().apply {
                    width = px
                    height = px
                    setMargins(margin, margin, margin, margin)
                }
            }
            grid.addView(frame)
            renderer.inflateAsync(LayoutElementBuilders.Layout.Builder().setRoot(element).build(), layouts.resources(), frame)
        }
        setContentView(ScrollView(this).apply { addView(grid) })
    }
}
