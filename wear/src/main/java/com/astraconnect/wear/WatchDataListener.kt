package com.astraconnect.wear

import com.astraconnect.shared.WearContract
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.WearableListenerService

/** Empfängt den Stand vom Handy (auch wenn die Uhr-App gerade nicht läuft) und zeichnet Tile und Complication neu. */
class WatchDataListener : WearableListenerService() {

    override fun onDataChanged(events: DataEventBuffer) {
        var changed = false
        for (e in events) {
            if (e.type != DataEvent.TYPE_CHANGED || e.dataItem.uri.path != WearContract.PATH_STATUS) continue
            val json = DataMapItem.fromDataItem(e.dataItem).dataMap.getString(WearContract.KEY_JSON) ?: continue
            WatchState.saveStatus(this, json)
            changed = true
        }
        if (changed) WatchState.refreshSurfaces(this)
    }
}
