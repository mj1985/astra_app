package com.astraconnect.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.time.Instant
import java.time.temporal.ChronoUnit

/** Lesender Zugriff auf die Stellantis Connected-Car-API v4. */
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

    private fun resolve(base: HttpUrl, href: String): HttpUrl? {
        val u = base.resolve(href) ?: return null
        // client_id muss bei jedem Aufruf mitgeschickt werden
        return if (u.queryParameter("client_id") == null)
            u.newBuilder().addQueryParameter("client_id", auth.clientId).build() else u
    }

    private fun url(vararg segments: String): HttpUrl =
        ApiConfig.API_BASE.toHttpUrl().newBuilder().apply {
            segments.forEach { addPathSegment(it) }
            addQueryParameter("client_id", auth.clientId)
            addQueryParameter("locale", ApiConfig.LOCALE)
        }.build()

    private suspend fun get(url: HttpUrl, retried: Boolean = false): JSONObject {
        val token = auth.accessToken(forceRefresh = retried)
        val (code, body) = withContext(Dispatchers.IO) {
            val req = Request.Builder().url(url)
                .header("Authorization", "Bearer $token")
                .header("x-introspect-realm", ApiConfig.REALM)
                .header("Accept", "application/hal+json")
                .build()
            http.newCall(req).execute().use { it.code to it.body?.string().orEmpty() }
        }
        if (code == 401 && !retried) return get(url, retried = true)
        if (code !in 200..299) {
            val detail = runCatching { JSONObject(body).let { it.optString("message").ifBlank { it.optString("title") } } }
                .getOrNull().orEmpty()
            throw ApiException(
                when (code) {
                    401, 403 -> "Kein Zugriff (HTTP $code). Ggf. neu anmelden."
                    404 -> "Nicht gefunden (HTTP 404)"
                    429 -> "Zu viele Anfragen – bitte später erneut versuchen."
                    else -> "API-Fehler HTTP $code"
                } + if (detail.isNotBlank()) " – $detail" else "",
                code
            )
        }
        return if (body.isBlank()) JSONObject() else JSONObject(body)
    }
}
