package com.astraconnect.app.wear

import com.astraconnect.app.data.Garage
import com.astraconnect.app.data.SnapshotWorker
import com.astraconnect.app.widget.ControlWidget
import com.astraconnect.app.widget.WidgetCommand
import com.astraconnect.app.widget.Widgets
import com.astraconnect.shared.WearContract
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService

/**
 * Nimmt Nachrichten der Uhr-App entgegen. Die Data Layer API stellt nur Nachrichten der eigenen
 * Uhr-App zu (gleiche Paket-ID und Signatur); erlaubt sind trotzdem nur die Befehle aus [WearContract.COMMANDS].
 */
class PhoneWearListener : WearableListenerService() {

    override fun onMessageReceived(event: MessageEvent) {
        when (event.path) {
            WearContract.PATH_COMMAND -> {
                val (name, requested) = WearContract.parseCommand(event.data) ?: return
                val cmd = runCatching { WidgetCommand.valueOf(name) }.getOrNull() ?: return
                // Fahrzeug, das die Uhr angezeigt hat – ist es nicht (mehr) im Konto, das gewählte
                val vehicleId = requested?.takeIf { id -> Garage.vehicles(this).any { it.id == id } } ?: Garage.selectedId(this)
                // Merkt den Befehl vor, aktualisiert Widgets und Uhr („Sende …“) und sendet im Hintergrund
                ControlWidget.enqueue(this, cmd, vehicleId)
            }
            WearContract.PATH_REFRESH -> {
                Widgets.updateAll(this)
                SnapshotWorker.runOnce(this)
            }
        }
    }
}
