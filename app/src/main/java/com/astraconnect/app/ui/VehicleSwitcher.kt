package com.astraconnect.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import com.astraconnect.app.AppViewModel
import com.astraconnect.app.data.CarModels
import com.astraconnect.app.data.Garage

/**
 * Name des gewählten Fahrzeugs als Überschrift. Bei mehreren Fahrzeugen im Konto öffnet ein Tipp
 * die Auswahl; mit nur einem ist es eine einfache Überschrift.
 */
@Composable
fun VehicleSwitcher(vm: AppViewModel) {
    val ui = vm.ui
    val multiple = ui.vehicles.size > 1
    var open by remember { mutableStateOf(false) }
    Box {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = if (multiple) Modifier.clickable(role = Role.DropdownList, onClickLabel = "Fahrzeug wechseln") { open = true } else Modifier,
        ) {
            Text(ui.vehicleName, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            if (multiple) Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            ui.vehicles.forEach { v ->
                val selected = v.id == ui.vehicle?.id
                val model = CarModels.find(v.vin)
                DropdownMenuItem(
                    text = {
                        Text(
                            Garage.displayName(v, model) + (model?.takeIf { v.label?.isNotBlank() == true }?.let { " · ${it.name}" } ?: ""),
                            fontWeight = if (selected) FontWeight.Bold else null,
                        )
                    },
                    leadingIcon = { Icon(Icons.Filled.DirectionsCar, null) },
                    trailingIcon = { if (selected) Icon(Icons.Filled.Check, "gewählt") },
                    onClick = {
                        open = false
                        vm.selectVehicle(v.id)
                    },
                )
            }
        }
    }
}
