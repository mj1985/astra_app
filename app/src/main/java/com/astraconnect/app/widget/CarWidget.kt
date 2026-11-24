package com.astraconnect.app.widget

import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import com.astraconnect.app.R
import com.astraconnect.app.data.Powertrain
import com.astraconnect.app.ui.km
import com.astraconnect.app.ui.pct
import kotlin.math.roundToInt

/**
 * Fahrzeug-Widget (4×2): Name, Akku- bzw. Tankbalken mit Reichweite, Ladestatus, Fahrzeugbild in der
 * Schrägansicht (Opel-Bild mit `view=002`) und eine Tastenleiste für Verriegeln, Klima, Laden und Licht.
 */
class CarWidget : BaseWidget() {

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        ControlWidget.handleCommand(context, intent)
    }

    companion object {
        /** Eigene Request-Codes, damit sich die Tasten nicht mit denen des Steuer-Widgets überschneiden. */
        private const val REQUEST_BASE = 200

        fun render(src: WidgetSource): RemoteViews {
            val ctx = src.ctx
            val s = src.status
            val pt = src.profile.powertrain
            val v = src.views(R.layout.widget_car)

            v.setTextViewText(R.id.w_name, src.name)

            // Akku (Elektro/Hybrid) bzw. Tank (Verbrenner) als Batteriesymbol mit Reichweite daneben
            val charging = pt.hasBattery && s?.charging?.isCharging == true
            v.setViewVisibility(R.id.w_bar_bat, if (pt.hasBattery) View.VISIBLE else View.GONE)
            v.setViewVisibility(R.id.w_bar_fuel, if (pt.hasBattery) View.GONE else View.VISIBLE)
            v.setProgressBar(R.id.w_bar_bat, 100, percent(s?.batteryPercent), false)
            v.setProgressBar(R.id.w_bar_fuel, 100, percent(s?.fuelPercent), false)
            v.setViewVisibility(R.id.w_bolt, if (charging) View.VISIBLE else View.GONE)
            v.setTextViewText(R.id.w_range, (if (pt.hasBattery) s?.electricRangeKm else s?.fuelRangeKm ?: s?.totalRangeKm).km())
            v.setTextColor(R.id.w_range, if (charging) COLOR_OK else COLOR_WHITE)
            v.setTextViewText(R.id.w_detail, detail(src, charging))

            // Fahrzeugbild: Schrägansicht, bis sie geladen ist die vorhandene Seitenansicht
            CarImage.ensure(ctx, src.vehicleId, CarView.FRONT)
            val car = CarImage.loadWhole(ctx, src.vehicleId, CarView.FRONT) ?: CarImage.loadWhole(ctx, src.vehicleId, CarView.SIDE)
            if (car != null) v.setImageViewBitmap(R.id.w_car, car)
            v.setTextViewText(R.id.w_updated, s?.updatedAt?.let { "↻ " + it.stand(src.now) } ?: "")
            v.setOnClickPendingIntent(R.id.w_top, src.openApp())

            // Tastenleiste
            val pending = PendingCommands.get(src.prefs)
            fun button(btn: Int, icon: Int, cmd: WidgetCommand, active: Boolean, tint: Int) {
                v.setInt(btn, "setBackgroundResource", if (active) R.drawable.widget_segment_active else 0)
                v.setInt(icon, "setColorFilter", tint)
                v.setInt(icon, "setImageAlpha", if (cmd.pendingKey(src.vehicleId) in pending) 90 else 255)
                v.setOnClickPendingIntent(btn, cmd.pendingIntent(ctx, CarWidget::class.java, REQUEST_BASE, src.vehicleId))
            }

            val locked = s?.isLocked == true
            button(R.id.w_btn_lock, R.id.w_icon_lock, WidgetCommand.LOCK, false, if (locked) COLOR_OK else COLOR_TEXT)

            val climateOn = s?.preconditioning.equals("Enabled", true)
            button(
                R.id.w_btn_climate, R.id.w_icon_climate,
                if (climateOn) WidgetCommand.CLIMATE_OFF else WidgetCommand.CLIMATE_ON,
                climateOn, if (climateOn) COLOR_ACCENT else COLOR_TEXT,
            )
            button(
                R.id.w_btn_charge, R.id.w_icon_charge,
                if (charging) WidgetCommand.CHARGE_STOP else WidgetCommand.CHARGE_START,
                charging, if (charging) COLOR_OK else COLOR_TEXT,
            )
            button(R.id.w_btn_light, R.id.w_icon_light, WidgetCommand.LIGHTS, false, COLOR_TEXT)

            // Ohne Antriebsbatterie weder Laden noch Vorklimatisierung aus der Ferne
            val remoteEnergy = if (pt.hasRemoteClimate) View.VISIBLE else View.GONE
            for (id in listOf(R.id.w_btn_climate, R.id.w_div_climate, R.id.w_btn_charge, R.id.w_div_charge)) {
                v.setViewVisibility(id, remoteEnergy)
            }
            return v
        }

        /** Zeile(n) unter der Reichweite: beim Laden bzw. Angesteckt der Ladestatus, sonst Akku-/Tankstand. */
        private fun detail(src: WidgetSource, charging: Boolean): String {
            val s = src.status ?: return "Noch keine Daten"
            val pt = src.profile.powertrain
            if (!pt.hasBattery) return "Tank ${s.fuelPercent.pct()}"
            if (charging || s.charging?.plugged == true) {
                val c = chargeText(s, src.now)
                return listOf(c.state, c.detail).filter { it.isNotBlank() }.joinToString("\n")
            }
            return listOfNotNull(
                "Akku ${s.batteryPercent.pct()}",
                if (pt == Powertrain.PHEV) "Gesamt ${s.totalRangeKm.km()}" else null,
            ).joinToString(" · ")
        }

        private fun percent(v: Double?) = (v ?: 0.0).roundToInt().coerceIn(0, 100)

        private const val COLOR_WHITE = 0xFFFFFFFF.toInt()
    }
}
