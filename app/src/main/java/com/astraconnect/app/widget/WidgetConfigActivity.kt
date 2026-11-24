package com.astraconnect.app.widget

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.SyncAlt
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.astraconnect.app.data.CarModels
import com.astraconnect.app.data.Garage
import com.astraconnect.app.ui.theme.AstraTheme

/**
 * Fahrzeug für ein neues Widget wählen. Mit nur einem Fahrzeug im Konto gibt es nichts zu wählen –
 * dann schließt sich die Seite sofort und das Widget folgt dem in der App gewählten Fahrzeug.
 * Ab Android 12 lässt sich die Wahl über „Widget bearbeiten“ ändern.
 */
class WidgetConfigActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val widgetId = intent?.extras?.getInt(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
            ?: AppWidgetManager.INVALID_APPWIDGET_ID
        // Zurück ohne Wahl = Widget nicht platzieren (so verlangt es der Launcher)
        setResult(Activity.RESULT_CANCELED, result(widgetId))
        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }

        val vehicles = Garage.vehicles(this)
        if (vehicles.size <= 1) {
            done(widgetId, null)
            return
        }

        val current = WidgetVehicles.chosen(this, widgetId)
        setContent {
            AstraTheme {
                Surface(color = MaterialTheme.colorScheme.surface) {
                    Column(Modifier.fillMaxWidth().padding(vertical = 16.dp)) {
                        Text(
                            "Welches Fahrzeug soll das Widget zeigen?",
                            style = MaterialTheme.typography.titleLarge,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                        vehicles.forEach { v ->
                            val model = CarModels.find(v.vin)
                            ListItem(
                                headlineContent = { Text(Garage.displayName(v, model)) },
                                supportingContent = {
                                    Text(listOfNotNull(model?.name, v.vin.takeLast(6).takeIf { it.isNotBlank() }?.let { "VIN …$it" }).joinToString(" · "))
                                },
                                leadingContent = { Icon(Icons.Filled.DirectionsCar, null) },
                                trailingContent = { RadioButton(selected = current == v.id, onClick = { done(widgetId, v.id) }) },
                                modifier = Modifier.clickable { done(widgetId, v.id) },
                            )
                        }
                        HorizontalDivider(Modifier.padding(vertical = 4.dp))
                        ListItem(
                            headlineContent = { Text("Dem in der App gewählten Fahrzeug folgen") },
                            supportingContent = { Text("Wechselt mit, wenn du in der App das Fahrzeug umschaltest.") },
                            leadingContent = { Icon(Icons.Filled.SyncAlt, null) },
                            trailingContent = { RadioButton(selected = current == null, onClick = { done(widgetId, null) }) },
                            modifier = Modifier.clickable { done(widgetId, null) },
                        )
                    }
                }
            }
        }
    }

    private fun result(widgetId: Int) = Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)

    private fun done(widgetId: Int, vehicleId: String?) {
        WidgetVehicles.set(this, widgetId, vehicleId)
        Widgets.updateAll(this)
        setResult(Activity.RESULT_OK, result(widgetId))
        finish()
    }
}
