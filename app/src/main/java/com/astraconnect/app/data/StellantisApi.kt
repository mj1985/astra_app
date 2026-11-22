package com.astraconnect.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.time.Instant
import java.time.temporal.ChronoUnit

/** Zugriff auf die Stellantis Connected-Car-API v4 (lesend, plus Navigationsziel senden). */
class StellantisApi(private val auth: AuthRepository, private val http: OkHttpClient) {

    suspend fun vehiclesRaw(): JSONObject = get(url("vehicles"))

    suspend fun vehicles(): List<Vehicle> =
        vehiclesRaw().obj("_embedded").arr("vehicles").objects().map(Vehicle::parse)

    suspend fun status(vehicleId: String): JSONObject = get(url("vehicles", vehicleId, "status"))

    /** Nicht bei allen Fahrzeugen vorhanden – liefert dann null. */
    suspend fun maintenance(vehicleId: String): JSONObject? =
        runCatching { get(url("vehicles", vehicleId, "maintenance")) }.getOrNull()

    /**
     * Fahrten (My Trip Report). Folgt der HAL-Paginierung über `_links.next` bzw. `pageToken`.
     */
    suspend fun trips(vehicleId: String, since: Instant?, maxPages: Int = 30): List<JSONObject> {
        val result = mutableListOf<JSONObject>()
        val sinceTs = (since ?: Instant.now().minus(365, ChronoUnit.DAYS)).truncatedTo(ChronoUnit.SECONDS)
        var next: HttpUrl? = url("vehicles", vehicleId, "trips").newBuilder()
            .addQueryParameter("timestamps", "$sinceTs/")
            .addQueryParameter("distance", "0.1-")
            .build()
        val seenTokens = mutableSetOf<String>()
        var pages = 0
        while (next != null && pages < maxPages) {
            pages++
            val page = get(next)
            val embedded = page.obj("_embedded")
            val list = embedded.arr("trips")
                ?: embedded?.keys()?.asSequence()?.mapNotNull { embedded.optJSONArray(it) }?.firstOrNull()
            result += list.objects()

            val links = page.obj("_links")
            val nextHref = links.pathStr("next", "href")
            next = when {
                nextHref != null -> resolve(next, nextHref)
                else -> {
                    val lastHref = links.pathStr("last", "href")
                    val selfHref = links.pathStr("self", "href")
                    val token = lastHref?.substringAfter("pageToken=", "")?.substringBefore("&")
                    if (lastHref != null && lastHref != selfHref && !token.isNullOrBlank() && seenTokens.add(token))
                        resolve(next, lastHref) else null
                }
            }
        }
        return result
    }

    private fun resolve(base: HttpUrl, href: String): HttpUrl? = resolvePageLink(base, href, auth.clientId)

    companion object {
        /**
         * Löst einen Blätter-Link aus der API-Antwort auf. Der Link darf nur auf denselben Server
         * (per HTTPS) zeigen – sonst würde das Zugangs-Token an eine fremde Adresse geschickt.
         */
        internal fun resolvePageLink(base: HttpUrl, href: String, clientId: String): HttpUrl? {
            val u = base.resolve(href) ?: return null
            if (!u.isHttps || u.host != base.host || u.port != base.port) {
                throw ApiException("Unerwarteter Link in der Fahrtenliste (${u.scheme}://${u.host}) – Abruf abgebrochen.")
            }
            // client_id muss bei jedem Aufruf mitgeschickt werden
            return if (u.queryParameter("client_id") == null)
                u.newBuilder().addQueryParameter("client_id", clientId).build() else u
        }
    }

    private fun url(vararg segments: String): HttpUrl =
        ApiConfig.API_BASE.toHttpUrl().newBuilder().apply {
            segments.forEach { addPathSegment(it) }
            addQueryParameter("client_id", auth.clientId)
            addQueryParameter("locale", auth.locale)
        }.build()

    // ------------------------------------------------------------------ Navigation (Remote über REST)

    /** Unterstützt das Fahrzeug „Ziel an Auto senden“? null = unbekannt (Abfrage nicht möglich). */
    suspend fun navigationSupported(vehicleId: String): Boolean? = runCatching {
        Navigation.supported(
            get(url("vehicles", vehicleId).newBuilder().addQueryParameter("extension", "onboardCapabilities").build())
        )
    }.getOrNull()

    /** Sendet ein Ziel (letzter Punkt) samt Zwischenzielen. Liefert Callback-ID und Remote-ID. */
    suspend fun sendNavigation(vehicleId: String, positions: List<LatLon>): Pair<String, String> {
        val cbid = remoteCallbackId()
        val resp = request(
            url("vehicles", vehicleId, "callbacks", cbid, "remotes"),
            Navigation.remoteBody(positions),
            what = "Schritt 2/2 (Ziel senden)",
        )
        val rid = Navigation.remoteActionId(resp) ?: throw ApiException("Opel hat keine Auftrags-ID zurückgegeben.")
        return cbid to rid
    }

    suspend fun navigationState(vehicleId: String, cbid: String, rid: String): NavState =
        Navigation.state(get(url("vehicles", vehicleId, "callbacks", cbid, "remotes", rid)))

    /** Eigener Callback für Remote-Befehle – vorhandenen wiederverwenden, sonst anlegen. */
    private suspend fun remoteCallbackId(): String {
        runCatching { Navigation.findCallbackId(get(url("callbacks"))) }.getOrNull()?.let { return it }
        val created = request(url("callbacks"), Navigation.callbackBody(), what = "Schritt 1/2 (Callback anlegen)")
        return Navigation.createdCallbackId(created) ?: throw ApiException("Opel hat keine Callback-ID zurückgegeben.")
    }

    private suspend fun get(url: HttpUrl, retried: Boolean = false): JSONObject = request(url, null, retried)

    /** GET (post == null) oder POST mit JSON-Body. */
    private suspend fun request(url: HttpUrl, post: JSONObject?, retried: Boolean = false, what: String? = null): JSONObject {
        val token = auth.accessToken(forceRefresh = retried)
        val (code, body) = withContext(Dispatchers.IO) {
            val b = Request.Builder().url(url)
                .header("Authorization", "Bearer $token")
                .header("x-introspect-realm", ApiConfig.REALM)
                .header("Accept", "application/hal+json")
            if (post != null) b.post(post.toString().toRequestBody("application/json".toMediaType()))
            http.newCall(b.build()).execute().use { it.code to it.body?.string().orEmpty() }
        }
        if (code == 401 && !retried) return request(url, post, retried = true, what = what)
        if (code !in 200..299) {
            val detail = runCatching { JSONObject(body).let { it.optString("message").ifBlank { it.optString("title") } } }
                .getOrNull().orEmpty()
            throw ApiException(
                when {
                    what != null && code in setOf(401, 403) ->
                        "$what: Opel verweigert den Zugriff (HTTP $code). Der MyOpel-Zugang darf diesen Befehl vermutlich nicht nutzen."
                    what != null && code == 404 -> "$what: Funktion nicht gefunden (HTTP 404)."
                    what != null && code >= 500 ->
                        "$what: Opel lehnt ab (HTTP $code). Häufigste Ursache: kein aktives Abo für die vernetzte Navigation."
                    else -> when (code) {
                    401, 403 -> "Kein Zugriff (HTTP $code). Ggf. neu anmelden."
                    404 -> "Nicht gefunden (HTTP 404)"
                    429 -> "Zu viele Anfragen – bitte später erneut versuchen."
                    else -> if (what != null) "$what: API-Fehler HTTP $code" else "API-Fehler HTTP $code"
                    }
                } + if (detail.isNotBlank()) " – $detail" else "",
                code
            )
        }
        return if (body.isBlank()) JSONObject() else JSONObject(body)
    }
}
