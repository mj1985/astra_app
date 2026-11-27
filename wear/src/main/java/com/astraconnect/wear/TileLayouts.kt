package com.astraconnect.wear

import android.content.Context
import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.ColorBuilders.argb
import androidx.wear.protolayout.DeviceParametersBuilders.DeviceParameters
import androidx.wear.protolayout.DimensionBuilders.dp
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.LayoutElementBuilders.LayoutElement
import androidx.wear.protolayout.ModifiersBuilders
import androidx.wear.protolayout.ResourceBuilders
import androidx.wear.protolayout.material.Button
import androidx.wear.protolayout.material.ButtonColors
import androidx.wear.protolayout.material.ChipColors
import androidx.wear.protolayout.material.CircularProgressIndicator
import androidx.wear.protolayout.material.CompactChip
import androidx.wear.protolayout.material.ProgressIndicatorColors
import androidx.wear.protolayout.material.Text
import androidx.wear.protolayout.material.Typography
import androidx.wear.protolayout.material.layouts.EdgeContentLayout
import androidx.wear.protolayout.material.layouts.PrimaryLayout
import com.astraconnect.shared.WearContract
import com.astraconnect.shared.WearStatus
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Kurzlebiger Zustand der Uhr, der im Tile angezeigt wird. */
data class TileUi(
    /** Tasten mit laufendem Befehl (vom Handy gemeldet oder gerade auf der Uhr abgeschickt). */
    val sending: Set<String> = emptySet(),
    val error: String? = null,
    val refreshing: Boolean = false,
)

/** Layouts des Tiles – ohne Zustand, damit sie sich auch mit Beispieldaten zeichnen lassen. */
class TileLayouts(private val ctx: Context) {

    fun resources(): ResourceBuilders.Resources = ResourceBuilders.Resources.Builder()
        .setVersion(RES_VERSION)
        .addIdToImageMapping(IC_CLIMATE, image(R.drawable.ic_climate))
        .addIdToImageMapping(IC_LOCK, image(R.drawable.ic_lock))
        .build()

    /**
     * Akku-Ring, Akkustand mit Reichweite (antippen = neuer Stand), Tasten, unten Ladestatus oder Stand.
     * Oben der Fahrzeugname, aber nur bei mehreren Fahrzeugen ([WearStatus.label]).
     */
    fun main(device: DeviceParameters, s: WearStatus, ui: TileUi): LayoutElement {
        val percent = s.mainPercent
        val ring = CircularProgressIndicator.Builder()
            .setProgress((percent ?: 0) / 100f)
            .setCircularProgressIndicatorColors(
                ProgressIndicatorColors(argb(if (s.charging) COLOR_OK else COLOR_ACCENT), argb(COLOR_TRACK)),
            )
            .build()

        val values = LayoutElementBuilders.Column.Builder()
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
            .addContent(text(percent?.let { "$it %" } ?: "–", Typography.TYPOGRAPHY_DISPLAY3, COLOR_TEXT))
            .addContent(text(s.rangeText, Typography.TYPOGRAPHY_CAPTION2, COLOR_MUTED))
            .build()

        val buttons = LayoutElementBuilders.Row.Builder()
            .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
        if (s.hasClimate) {
            val busy = WearContract.KEY_CLIMATE in ui.sending
            buttons.addContent(
                button(
                    id = if (busy) BUSY else ASK + if (s.climateOn) WearContract.CLIMATE_OFF else WearContract.CLIMATE_ON,
                    icon = IC_CLIMATE,
                    description = if (s.climateOn) "Klimatisierung stoppen" else "Klimatisierung starten",
                    colors = when {
                        busy -> ButtonColors(argb(COLOR_SURFACE), argb(COLOR_DIM))
                        s.climateOn -> ButtonColors(argb(COLOR_ACCENT), argb(COLOR_DARK))
                        else -> ButtonColors(argb(COLOR_SURFACE), argb(COLOR_TEXT))
                    },
                ),
            )
            buttons.addContent(LayoutElementBuilders.Spacer.Builder().setWidth(dp(BUTTON_GAP_DP)).build())
        }
        val lockBusy = WearContract.KEY_LOCK in ui.sending
        buttons.addContent(
            button(
                id = if (lockBusy) BUSY else ASK + WearContract.LOCK,
                icon = IC_LOCK,
                description = if (s.locked == true) "Verriegelt – erneut verriegeln" else "Verriegeln",
                colors = when {
                    lockBusy -> ButtonColors(argb(COLOR_SURFACE), argb(COLOR_DIM))
                    s.locked == true -> ButtonColors(argb(COLOR_SURFACE), argb(COLOR_OK))
                    else -> ButtonColors(argb(COLOR_SURFACE), argb(COLOR_TEXT))
                },
            ),
        )

        val content = LayoutElementBuilders.Column.Builder()
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
            .addContent(clickableBox(REFRESH, values))
            .addContent(LayoutElementBuilders.Spacer.Builder().setHeight(dp(4f)).build())
            .addContent(buttons.build())
            .build()

        val (bottom, bottomColor) = when {
            ui.error != null -> ui.error to COLOR_ERROR
            ui.sending.isNotEmpty() -> "Sende …" to COLOR_ACCENT
            ui.refreshing -> "Aktualisiere …" to COLOR_ACCENT
            s.charging -> s.chargeText to COLOR_OK
            else -> "Stand ${stand(s.updatedAt)}" to COLOR_MUTED
        }
        return EdgeContentLayout.Builder(device)
            .setEdgeContent(ring)
            .apply { s.label?.let { setPrimaryLabelTextContent(text(it, Typography.TYPOGRAPHY_CAPTION1, COLOR_MUTED)) } }
            .setContent(content)
            .setSecondaryLabelTextContent(text(bottom, Typography.TYPOGRAPHY_CAPTION2, bottomColor))
            .build()
    }

    /** Rückfrage vor dem Senden – „Abbrechen“ oder nach 15 s zurück zur Übersicht. */
    fun confirm(device: DeviceParameters, s: WearStatus, cmd: String): LayoutElement {
        val (question, action) = when (cmd) {
            WearContract.CLIMATE_ON -> "Klimatisierung starten?" to "Starten"
            WearContract.CLIMATE_OFF -> "Klimatisierung stoppen?" to "Stoppen"
            else -> "Auto verriegeln?" to "Verriegeln"
        }
        val content = LayoutElementBuilders.Column.Builder()
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
            .addContent(text(question, Typography.TYPOGRAPHY_TITLE3, COLOR_TEXT, maxLines = 2))
            .addContent(clickableBox(CANCEL, text("Abbrechen", Typography.TYPOGRAPHY_CAPTION1, COLOR_MUTED)))
            .build()
        return PrimaryLayout.Builder(device)
            .apply { s.label?.let { setPrimaryLabelTextContent(text(it, Typography.TYPOGRAPHY_CAPTION1, COLOR_MUTED)) } }
            .setContent(content)
            .setPrimaryChipContent(
                CompactChip.Builder(ctx, action, clickable(SEND + cmd), device)
                    .setChipColors(ChipColors(argb(COLOR_ACCENT), argb(COLOR_DARK)))
                    .build(),
            )
            .build()
    }

    fun noData(device: DeviceParameters, error: String?): LayoutElement =
        PrimaryLayout.Builder(device)
            .setPrimaryLabelTextContent(text(ctx.getString(R.string.app_name), Typography.TYPOGRAPHY_CAPTION1, COLOR_ACCENT))
            .setContent(
                text(error ?: "Noch keine Daten vom Handy. BlitzLink auf dem Handy öffnen.", Typography.TYPOGRAPHY_BODY2, COLOR_TEXT, maxLines = 3),
            )
            .setPrimaryChipContent(
                CompactChip.Builder(ctx, "Abrufen", clickable(REFRESH), device)
                    .setChipColors(ChipColors(argb(COLOR_SURFACE), argb(COLOR_TEXT)))
                    .build(),
            )
            .build()

    private fun text(value: String, typography: Int, color: Int, maxLines: Int = 1): LayoutElement =
        Text.Builder(ctx, value)
            .setTypography(typography)
            .setColor(argb(color))
            .setMaxLines(maxLines)
            .build()

    private fun clickable(id: String) = ModifiersBuilders.Clickable.Builder()
        .setId(id)
        .setOnClick(ActionBuilders.LoadAction.Builder().build())
        .build()

    private fun clickableBox(id: String, child: LayoutElement): LayoutElement =
        LayoutElementBuilders.Box.Builder()
            .setModifiers(
                ModifiersBuilders.Modifiers.Builder()
                    .setClickable(clickable(id))
                    .setPadding(ModifiersBuilders.Padding.Builder().setAll(dp(4f)).build())
                    .build(),
            )
            .addContent(child)
            .build()

    private fun button(id: String, icon: String, description: String, colors: ButtonColors): LayoutElement =
        Button.Builder(ctx, clickable(id))
            .setIconContent(icon)
            .setContentDescription(description)
            .setSize(dp(BUTTON_DP))
            .setButtonColors(colors)
            .build()

    private fun image(res: Int) = ResourceBuilders.ImageResource.Builder()
        .setAndroidResourceByResId(ResourceBuilders.AndroidImageResourceByResId.Builder().setResourceId(res).build())
        .build()

    companion object {
        const val RES_VERSION = "1"
        private const val IC_CLIMATE = "climate"
        private const val IC_LOCK = "lock"

        // IDs der antippbaren Flächen; ASK/SEND + Befehlsname aus WearContract.COMMANDS
        const val ASK = "ask:"
        const val SEND = "send:"
        const val REFRESH = "refresh"
        const val CANCEL = "cancel"
        const val BUSY = "busy"

        private const val BUTTON_DP = 44f
        private const val BUTTON_GAP_DP = 10f

        private const val COLOR_ACCENT = 0xFFF7FF14.toInt()
        private const val COLOR_OK = 0xFF4CC27E.toInt()
        private const val COLOR_ERROR = 0xFFFF7A70.toInt()
        private const val COLOR_TEXT = 0xFFE6E6E6.toInt()
        private const val COLOR_MUTED = 0xFFA8A8A8.toInt()
        private const val COLOR_DIM = 0xFF6B6B6B.toInt()
        private const val COLOR_SURFACE = 0xFF303134.toInt()
        private const val COLOR_TRACK = 0xFF333333.toInt()
        private const val COLOR_DARK = 0xFF1A1A00.toInt()

        private val timeFormat = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault())
        private val dayTimeFormat = DateTimeFormatter.ofPattern("dd.MM. HH:mm").withZone(ZoneId.systemDefault())

        /** „14:05“ für heute, sonst „20.11. 14:05“. */
        fun stand(epochMs: Long?): String {
            if (epochMs == null) return "–"
            val at = Instant.ofEpochMilli(epochMs)
            val today = at.atZone(ZoneId.systemDefault()).toLocalDate() == LocalDate.now()
            return (if (today) timeFormat else dayTimeFormat).format(at)
        }
    }
}
