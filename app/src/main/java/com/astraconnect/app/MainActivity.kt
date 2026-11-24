package com.astraconnect.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.astraconnect.app.ui.AppRoot
import com.astraconnect.app.ui.theme.AstraTheme

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Nur beim ersten Start aus dem Widget umschalten, nicht nach Drehen des Bildschirms
        if (savedInstanceState == null) openVehicleFrom(intent)
        setContent {
            AstraTheme {
                AppRoot(vm)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        openVehicleFrom(intent)
    }

    /** Tipp auf ein Widget öffnet die App mit dem Fahrzeug dieses Widgets. */
    private fun openVehicleFrom(intent: Intent?) {
        intent?.getStringExtra(EXTRA_VEHICLE)?.let(vm::selectVehicle)
    }

    companion object {
        const val EXTRA_VEHICLE = "vehicle_id"
    }
}
