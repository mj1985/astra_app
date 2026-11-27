package com.astraconnect.wear

import android.graphics.drawable.Icon
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.MonochromaticImage
import androidx.wear.watchface.complications.data.NoDataComplicationData
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.RangedValueComplicationData
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService

/** Complication fürs Zifferblatt: Akkustand als Ring (RANGED_VALUE) oder Text (SHORT_TEXT); beim Verbrenner der Tank. */
class BatteryComplicationService : SuspendingComplicationDataSourceService() {

    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? {
        val s = WatchState.status(this) ?: return NoDataComplicationData()
        val percent = s.mainPercent ?: return NoDataComplicationData()
        return data(request.complicationType, percent, s.hasBattery, s.charging)
    }

    override fun getPreviewData(type: ComplicationType): ComplicationData? =
        data(type, 78, hasBattery = true, charging = false)

    private fun data(type: ComplicationType, percent: Int, hasBattery: Boolean, charging: Boolean): ComplicationData? {
        val text = PlainComplicationText.Builder("$percent%").build()
        val description = PlainComplicationText.Builder(
            (if (hasBattery) "Akku" else "Tank") + " $percent %" + if (charging) ", lädt" else "",
        ).build()
        val icon = MonochromaticImage.Builder(
            Icon.createWithResource(this, if (hasBattery) R.drawable.ic_bolt else R.drawable.ic_fuel),
        ).build()
        return when (type) {
            ComplicationType.RANGED_VALUE ->
                RangedValueComplicationData.Builder(percent.toFloat(), 0f, 100f, description)
                    .setText(text)
                    .setMonochromaticImage(icon)
                    .build()
            ComplicationType.SHORT_TEXT ->
                ShortTextComplicationData.Builder(text, description)
                    .setMonochromaticImage(icon)
                    .build()
            else -> null
        }
    }
}
