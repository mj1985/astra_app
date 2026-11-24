package com.astraconnect.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

class ApiException(message: String, val httpCode: Int = 0, val oauthError: String? = null) : Exception(message)
class AuthExpiredException(message: String) : Exception(message)

/**
 * OAuth2 (Authorization-Code-Flow) gegen das Opel/Stellantis-Login.
 * Das Passwort berührt die App nie direkt – der Login passiert im Web-Formular von Opel,
 * die App fängt nur den Redirect `mymopsdk://oauth2redirect/<land>?code=…` ab.
 */
class AuthRepository(private val store: SecureStore, private val http: OkHttpClient) {

    // Prozessweit EINE Sperre: App und Hintergrund-Worker erzeugen je eine eigene AuthRepository.
    // Mit getrennten Sperren konnten beide gleichzeitig das Refresh-Token einlösen – Opel tauscht
    // es dabei aus, der zweite Aufruf scheiterte mit invalid_grant und die App meldete sich ab.
    private val mutex get() = GLOBAL_MUTEX

    /** Gewählter MyOpel-Markt (Land). Bestimmt Locale, Redirect und die Standard-Client-ID. */
    var country: String
        get() = market.code
        set(v) {
            val m = ApiConfig.market(v)
            store.put(K_COUNTRY, m.code)
            Currency.current = m.currency
        }

    val market: ApiConfig.Market get() = ApiConfig.market(store.get(K_COUNTRY))

    init {
        Currency.current = market.currency
    }
    val locale: String get() = market.locale
    val redirectUri: String get() = market.redirectUri

    var clientId: String
        get() = store.get(K_CLIENT_ID)?.takeIf { it.isNotBlank() } ?: market.clientId
        set(v) = store.put(K_CLIENT_ID, v.trim())

    /**
     * Selbst eingetragenes Secret, sonst das beim Build hinterlegte. Entspricht der Eintrag dem
     * hinterlegten Wert, wird nichts gespeichert – so greift ein neues Secret nach einem App-Update.
     * Setzt voraus, dass [clientId] vorher gesetzt wurde.
     */
    var clientSecret: String
        get() = store.get(K_CLIENT_SECRET)?.takeIf { it.isNotBlank() } ?: ApiConfig.builtInSecret(clientId)
        set(v) {
            val s = v.trim()
            store.put(K_CLIENT_SECRET, if (s == ApiConfig.builtInSecret(clientId)) "" else s)
        }

    val isLoggedIn: Boolean get() = store.get(K_REFRESH) != null || store.get(K_ACCESS) != null

    fun authorizeUrl(): String =
        "${ApiConfig.OAUTH_BASE}/authorize".toHttpUrl().newBuilder()
            .addQueryParameter("client_id", clientId)
            .addQueryParameter("response_type", "code")
            .addQueryParameter("redirect_uri", redirectUri)
            .addQueryParameter("scope", "openid profile email")
            .addQueryParameter("locale", locale)
            .build().toString()

    suspend fun exchangeCode(code: String) = mutex.withLock {
        tokenRequest(
            FormBody.Builder()
                .add("grant_type", "authorization_code")
                .add("code", code.trim())
                .add("redirect_uri", redirectUri)
                .build()
        )
    }

    /** Liefert ein gültiges Access-Token und erneuert es bei Bedarf per Refresh-Token. */
    suspend fun accessToken(forceRefresh: Boolean = false): String = mutex.withLock {
        val access = store.get(K_ACCESS)
        val expiresAt = store.get(K_EXPIRES)?.toLongOrNull() ?: 0L
        if (!forceRefresh && access != null && System.currentTimeMillis() < expiresAt - 60_000) {
            return@withLock access
        }
        val refresh = store.get(K_REFRESH)
            ?: throw AuthExpiredException("Nicht angemeldet. Bitte erneut einloggen.")
        try {
            tokenRequest(
                FormBody.Builder()
                    .add("grant_type", "refresh_token")
                    .add("refresh_token", refresh)
                    .build()
            )
        } catch (e: ApiException) {
            // Nur abmelden, wenn der Server das Refresh-Token ausdrücklich ablehnt.
            // Netzfehler, 5xx oder sonstige 400er lassen die Anmeldung bestehen.
            val rejected = e.httpCode == 401 ||
                (e.httpCode == 400 && (e.oauthError == null || e.oauthError in setOf("invalid_grant", "invalid_token")))
            if (rejected && store.get(K_REFRESH) == refresh) {
                logout()
                throw AuthExpiredException("Sitzung abgelaufen. Bitte erneut einloggen.")
            }
            throw e
        }
        store.get(K_ACCESS) ?: throw AuthExpiredException("Kein Token erhalten.")
    }

    private suspend fun tokenRequest(form: FormBody) = withContext(Dispatchers.IO) {
        if (clientSecret.isBlank()) throw ApiException("Client-Secret fehlt (siehe Login-Einstellungen).")
        val req = Request.Builder()
            .url("${ApiConfig.OAUTH_BASE}/access_token")
            .header("Authorization", Credentials.basic(clientId, clientSecret))
            .post(form)
            .build()
        http.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                val err = runCatching { JSONObject(body) }.getOrNull()
                val msg = err?.optString("error_description")
                throw ApiException(
                    "Login fehlgeschlagen (HTTP ${resp.code})" + if (!msg.isNullOrBlank()) ": $msg" else "",
                    resp.code,
                    err?.optString("error")?.takeIf { it.isNotBlank() },
                )
            }
            val json = JSONObject(body)
            store.put(K_ACCESS, json.getString("access_token"))
            json.optString("refresh_token").takeIf { it.isNotBlank() }?.let { store.put(K_REFRESH, it) }
            val expiresIn = json.optLong("expires_in", 3600)
            store.put(K_EXPIRES, (System.currentTimeMillis() + expiresIn * 1000).toString())
        }
    }

    fun logout() {
        store.remove(K_ACCESS, K_REFRESH, K_EXPIRES)
    }

    private companion object {
        val GLOBAL_MUTEX = Mutex()
        const val K_COUNTRY = "country"
        const val K_CLIENT_ID = "client_id"
        const val K_CLIENT_SECRET = "client_secret"
        const val K_ACCESS = "access_token"
        const val K_REFRESH = "refresh_token"
        const val K_EXPIRES = "expires_at"
    }
}
