package com.astraconnect.wear

import android.content.Context
import android.net.Uri
import com.astraconnect.shared.WearContract
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.PutDataRequest
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.tasks.await

/** Verbindung zur Handy-App über die Wearable Data Layer API. */
object PhoneLink {

    /** Schickt eine Nachricht an das Handy mit BlitzLink. false, wenn keins erreichbar ist. */
    suspend fun send(ctx: Context, path: String, data: ByteArray = ByteArray(0)): Boolean {
        val nodes = Wearable.getCapabilityClient(ctx)
            .getCapability(WearContract.CAPABILITY_PHONE, CapabilityClient.FILTER_REACHABLE).await().nodes
        val client = Wearable.getMessageClient(ctx)
        var sent = false
        for (node in nodes) {
            if (runCatching { client.sendMessage(node.id, path, data).await() }.isSuccess) sent = true
        }
        return sent
    }

    /** Zuletzt vom Handy geschriebener Stand (JSON) – z. B. direkt nach der Installation der Uhr-App. */
    suspend fun fetchStatus(ctx: Context): String? {
        val uri = Uri.Builder().scheme(PutDataRequest.WEAR_URI_SCHEME).path(WearContract.PATH_STATUS).build()
        val items = Wearable.getDataClient(ctx).getDataItems(uri).await()
        return try {
            items.firstNotNullOfOrNull { DataMapItem.fromDataItem(it).dataMap.getString(WearContract.KEY_JSON) }
        } finally {
            items.release()
        }
    }
}
