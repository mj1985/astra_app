package com.astraconnect.app.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken
import org.eclipse.paho.client.mqttv3.MqttCallback
import org.eclipse.paho.client.mqttv3.MqttClient
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import org.json.JSONArray
import org.json.JSONObject
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Rückmeldung zu einem gesendeten Fernbefehl. */
data class CommandUpdate(
    val correlationId: String,
    val code: String,
    val label: String,
    val final: Boolean,
    val success: Boolean,
)

/** Dienst und Parameter eines Fernbefehls – gemeinsam für App und Widget. */
data class RemoteCommand(val service: String, val params: JSONObject) {
    companion object {
        fun lock() = RemoteCommand("/Doors", JSONObject().put("action", "lock"))
        fun unlock() = RemoteCommand("/Doors", JSONObject().put("action", "unlock"))
        fun horn() = RemoteCommand("/Horn", JSONObject().put("nb_horn", "2").put("action", "activate"))
        fun lights() = RemoteCommand("/Lights", JSONObject().put("duration", "10").put("action", "activate"))

        /**
         * Laden sofort starten bzw. pausieren. Pausieren = verzögertes Laden ab der im Auto programmierten
         * Startzeit ([nextDelayedTime], sonst 22:00).
         */
        fun charging(start: Boolean, nextDelayedTime: String?): RemoteCommand {
            val (h, m) = RemoteControl.ptTime(nextDelayedTime) ?: (22 to 0)
            return RemoteCommand(
                "/VehCharge",
                JSONObject()
                    .put("program", JSONObject().put("hour", h).put("minute", m))
                    .put("type", if (start) "immediate" else "delayed"),
            )
        }

        /** [status] = zuletzt abgerufener Status, damit gespeicherte Programme erhalten bleiben. */
        fun preconditioning(on: Boolean, status: JSONObject?) = RemoteCommand(
            "/ThermalPrecond",
            JSONObject()
                .put("asap", if (on) "activate" else "deactivate")
                .put("programs", RemoteControl.preconditioningPrograms(status)),
        )
    }
}

/**
 * Fernsteuerung über den MQTT-Dienst von Stellantis (wie die MyOpel-App).
 *
 *  - Einrichtung: SMS-Code anfordern → mit SMS-Code + MyOpel-PIN den OTP-Generator aktivieren.
 *  - Befehle: MQTT-Token holen (per Refresh-Token, sonst per neuem OTP-Code), per TLS mit
 *    mwa.mpsa.com:8885 verbinden, Befehl veröffentlichen, Antworten über das Antwort-Topic empfangen.
 */
class RemoteControl(
    private val store: SecureStore,
    private val auth: AuthRepository,
    private val http: OkHttpClient,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    // Prozessweit eine Sperre: App und Widget-Job dürfen nicht gleichzeitig einen OTP-Code verbrauchen.
    private val tokenMutex get() = TOKEN_MUTEX
    private val mqttMutex = Mutex()
    private val _updates = MutableSharedFlow<CommandUpdate>(extraBufferCapacity = 64)
    val updates = _updates.asSharedFlow()

    private var client: MqttClient? = null
    private var connectedToken: String? = null
    private var idleJob: Job? = null
    private val pending = ConcurrentHashMap<String, Pending>()

    private data class Pending(val vin: String, val service: String, val params: JSONObject, var retried: Boolean = false)

    val isActivated: Boolean get() = store.get(K_OTP) != null && store.get(K_CUSTOMER) != null

    // ------------------------------------------------------------------ Einrichtung

    /** Ermittelt die Kunden-ID und lässt Stellantis eine SMS mit Aktivierungscode schicken. */
    suspend fun requestSms() = withContext(Dispatchers.IO) {
        val assoc = call(apiUrl("/applications/cvs/v4/mauv/car-associations"), extra = mapOf("x-transaction-id" to "1234"))
        val arr = runCatching { JSONArray(assoc) }.getOrNull()
        val customer = arr?.optJSONObject(0)?.optString("customer").orEmpty()
        if (customer.isBlank()) throw ApiException("Kunden-ID konnte nicht ermittelt werden.")
        store.put(K_CUSTOMER, customer)
        call(apiUrl("/applications/cvs/v4/mobile/smsCode"), post = "")
        Unit
    }

    /** Aktiviert den OTP-Generator und holt testweise das erste MQTT-Token. */
    suspend fun activate(smsCode: String, pin: String) = withContext(Dispatchers.IO) {
        val otp = Otp.newSession()
        otp.activate(http, smsCode, pin)
        if (otp.missingSecret()) throw ApiException("Aktivierung unvollständig (kein Sicherheitsschlüssel vom OTP-Server). Bitte neue SMS anfordern.")
        store.put(K_OTP, otp.toJson())
        store.remove(K_MQTT_ACCESS, K_MQTT_REFRESH, K_MQTT_EXPIRES, K_MQTT_REFRESH_EXPIRES)
        try {
            mqttToken(force = true)
        } catch (e: ApiException) {
            // Halb eingerichteten Zustand nicht stehen lassen, sonst gilt die Fernsteuerung als aktiv.
            store.remove(K_OTP, K_MQTT_ACCESS, K_MQTT_REFRESH, K_MQTT_EXPIRES, K_MQTT_REFRESH_EXPIRES)
            if (e.httpCode == 400 && e.oauthError == "invalid_grant") throw ApiException(
                "Opel hat den Einmal-Code abgelehnt (invalid_grant). Häufigste Ursache: falsche PIN – gemeint ist " +
                    "die 4-stellige PIN, die du in der MyOpel-App für Fernbefehle festgelegt hast (nicht das Passwort). " +
                    "Außerdem erlaubt Opel nur wenige Codes pro Tag; nach mehreren Versuchen bis morgen warten.",
                400, "invalid_grant",
            )
            throw e
        }
        Unit
    }

    fun reset() {
        disconnect()
        store.remove(K_OTP, K_CUSTOMER, K_MQTT_ACCESS, K_MQTT_REFRESH, K_MQTT_EXPIRES, K_MQTT_REFRESH_EXPIRES)
    }

    // ------------------------------------------------------------------ Befehle

    /** Sendet einen Befehl und liefert die Korrelations-ID. Ergebnisse kommen über [updates]. */
    suspend fun send(vin: String, service: String, params: JSONObject): String {
        val id = UUID.randomUUID().toString().replace("-", "") +
            ZonedDateTime.now(ZoneOffset.UTC).format(DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS"))
        pending[id] = Pending(vin, service, params)
        publish(id, forceToken = false)
        return id
    }

    private suspend fun publish(correlationId: String, forceToken: Boolean) = withContext(Dispatchers.IO) {
        val p = pending[correlationId] ?: return@withContext
        val token = mqttToken(force = forceToken)
        val customer = store.get(K_CUSTOMER) ?: throw ApiException("Fernsteuerung nicht eingerichtet.")
        val c = ensureConnected(token, customer)
        val payload = JSONObject()
            .put("access_token", token)
            .put("customer_id", customer)
            .put("correlation_id", correlationId)
            .put("req_date", ZonedDateTime.now(ZoneOffset.UTC).format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'")))
            .put("vin", p.vin)
            .put("req_parameters", p.params)
        c.publish(REQ_TOPIC + customer + p.service, MqttMessage(payload.toString().toByteArray()).apply { qos = 0; isRetained = false })
        scheduleIdleDisconnect()
    }

    private suspend fun ensureConnected(token: String, customer: String): MqttClient = mqttMutex.withLock {
        client?.let { if (it.isConnected && connectedToken == token) return@withLock it }
        disconnectInternal()
        val c = MqttClient("ssl://$MQTT_HOST:$MQTT_PORT", "", MemoryPersistence())
        val opts = MqttConnectOptions().apply {
            userName = MQTT_USER
            password = token.toCharArray()
            isCleanSession = true
            keepAliveInterval = 120
            connectionTimeout = 20
            mqttVersion = MqttConnectOptions.MQTT_VERSION_3_1_1
            isAutomaticReconnect = false
        }
        c.setCallback(object : MqttCallback {
            override fun connectionLost(cause: Throwable?) {}
            override fun deliveryComplete(token: IMqttDeliveryToken?) {}
            override fun messageArrived(topic: String, message: MqttMessage) = onMessage(topic, String(message.payload))
        })
        try {
            c.connect(opts)
        } catch (e: Exception) {
            throw ApiException("Verbindung zum Opel-Fernsteuerungsdienst fehlgeschlagen: ${e.message ?: e.javaClass.simpleName}")
        }
        c.subscribe("$RESP_TOPIC$customer/#", 0)
        client = c
        connectedToken = token
        c
    }

    private fun onMessage(topic: String, payload: String) {
        if (!topic.startsWith(RESP_TOPIC)) return
        val data = runCatching { JSONObject(payload) }.getOrNull() ?: return
        val id = data.optString("correlation_id").ifBlank { return }
        val p = pending[id]
        var code = data.optString("return_code").ifBlank { data.optString("process_code") }
        if (code.isBlank()) return

        if (code == "400") {
            if (data.optString("reason") == "[authorization.denied.cvs.response.no.matching.service.key]") {
                code = "not_compatible"
            } else if (p != null && !p.retried) {
                // Token vermutlich ungültig → einmal mit frischem Token wiederholen
                p.retried = true
                _updates.tryEmit(CommandUpdate(id, code, "Token erneuern und erneut senden …", final = false, success = false))
                scope.launch {
                    runCatching { publish(id, forceToken = true) }.onFailure {
                        _updates.tryEmit(CommandUpdate(id, "failed", "Fehlgeschlagen: ${it.message}", final = true, success = false))
                    }
                }
                return
            } else code = "failed"
        }
        if (code == "113") code = "failed"

        val final = code !in IN_PROGRESS
        val success = code == "0" || code == "1"
        val label = describe(code, p?.service, data.optJSONObject("resp_data"), success)
        if (final) pending.remove(id)
        _updates.tryEmit(CommandUpdate(id, code, label, final, success))
    }

    private fun describe(code: String, service: String?, resp: JSONObject?, success: Boolean): String {
        val base = when (code) {
            "0", "1" -> "Erfolgreich ausgeführt"
            "900" -> "Vom Server angenommen"
            "901" -> "Fahrzeug schläft – wird geweckt …"
            "903" -> "An Fahrzeug übermittelt"
            "300", "500", "failed" -> "Fehlgeschlagen"
            "not_compatible" -> "Vom Fahrzeug nicht unterstützt"
            else -> "Status $code"
        }
        if (success || resp == null) return base
        val reason = when {
            service == "/Doors" && resp.has("lock_resp_state") -> when (resp.optInt("lock_resp_state", -1)) {
                2 -> "eine Tür ist offen"
                3 -> "Anfrage abgelehnt"
                5 -> "Schlüssel im Fahrzeug"
                else -> null
            }
            resp.has("error") -> when (service) {
                "/ThermalPrecond" -> when (resp.optInt("error", -1)) {
                    0, 3 -> "Fahrzeug nicht abgestellt"
                    1 -> "läuft bereits"
                    2 -> "Kommunikationsproblem"
                    4 -> "läuft nicht"
                    else -> null
                }
                "/VehCharge", "/VehCharge/state" -> when (resp.optInt("error", -1)) {
                    0 -> "Fahrzeug im Standby"
                    1 -> "Ladung läuft bereits"
                    2 -> "Kommunikationsproblem"
                    3 -> "Fahrzeug nicht abgestellt"
                    else -> null
                }
                "/Horn", "/Lights" -> when (resp.optInt("error", -1)) {
                    0 -> "Fahrzeug nicht abgestellt"
                    1, 2 -> "bitte kurz warten und erneut senden"
                    else -> null
                }
                else -> null
            }
            else -> null
        }
        return if (reason != null) "$base – $reason" else base
    }

    private fun scheduleIdleDisconnect() {
        idleJob?.cancel()
        idleJob = scope.launch {
            delay(5 * 60_000)
            disconnect()
        }
    }

    fun disconnect() {
        scope.launch { mqttMutex.withLock { disconnectInternal() } }
    }

    private fun disconnectInternal() {
        client?.let { c -> runCatching { if (c.isConnected) c.disconnect(2000) }; runCatching { c.close() } }
        client = null
        connectedToken = null
    }

    // ------------------------------------------------------------------ Tokens

    private suspend fun mqttToken(force: Boolean): String = tokenMutex.withLock {
        val now = System.currentTimeMillis()
        val access = store.get(K_MQTT_ACCESS)
        val expires = store.get(K_MQTT_EXPIRES)?.toLongOrNull() ?: 0
        if (!force && access != null && now < expires - 60_000) return@withLock access

        val refresh = store.get(K_MQTT_REFRESH)
        val refreshExpires = store.get(K_MQTT_REFRESH_EXPIRES)?.toLongOrNull() ?: 0
        val url = apiUrl("/connectedcar/v4/virtualkey/remoteaccess/token")

        var json: JSONObject? = null
        if (refresh != null && now < refreshExpires) {
            json = runCatching {
                JSONObject(call(url, post = JSONObject().put("grant_type", "refresh_token").put("refresh_token", refresh).toString()))
            }.getOrNull()?.takeIf { it.has("access_token") }
        }
        if (json == null) {
            val otpJson = store.get(K_OTP) ?: throw ApiException("Fernsteuerung nicht eingerichtet.")
            val otp = Otp.fromJson(otpJson)
            val code = try {
                withContext(Dispatchers.IO) { otp.nextCode(http) }
            } finally {
                store.put(K_OTP, otp.toJson())
            }
            json = JSONObject(call(url, post = JSONObject().put("grant_type", "password").put("password", code).toString()))
            if (!json.has("access_token")) throw ApiException("Kein Fernsteuerungs-Token erhalten. Ggf. Fernsteuerung neu einrichten.")
        }
        store.put(K_MQTT_ACCESS, json.getString("access_token"))
        store.put(K_MQTT_EXPIRES, (now + json.optLong("expires_in", 3600) * 1000).toString())
        json.optString("refresh_token").takeIf { it.isNotBlank() }?.let {
            store.put(K_MQTT_REFRESH, it)
            store.put(K_MQTT_REFRESH_EXPIRES, (now + 3L * 24 * 3600 * 1000).toString())
        }
        json.getString("access_token")
    }

    private fun apiUrl(path: String): HttpUrl = ("https://api.groupe-psa.com$path").toHttpUrl().newBuilder()
        .addQueryParameter("client_id", auth.clientId)
        .addQueryParameter("locale", auth.locale)
        .build()

    /** HTTP-Aufruf mit den Headern der MyOpel-App. post == null → GET. */
    private suspend fun call(url: HttpUrl, post: String? = null, extra: Map<String, String> = emptyMap()): String {
        val token = auth.accessToken()
        return withContext(Dispatchers.IO) {
            val b = Request.Builder().url(url)
                .header("Authorization", "Bearer $token")
                .header("User-Agent", "okhttp/4.8.0")
                .header("Accept", "application/hal+json")
                .header("x-introspect-realm", ApiConfig.REALM)
            extra.forEach { (k, v) -> b.header(k, v) }
            if (post != null) b.post(post.toRequestBody("application/json".toMediaType()))
            http.newCall(b.build()).execute().use { r ->
                val body = r.body?.string().orEmpty()
                if (!r.isSuccessful) {
                    val err = runCatching { JSONObject(body) }.getOrNull()
                    val msg = err?.let { it.optString("message").ifBlank { it.optString("error_description") } }
                    throw ApiException(
                        "Fernsteuerung: HTTP ${r.code}" + if (!msg.isNullOrBlank()) " – $msg" else "",
                        r.code,
                        err?.optString("error")?.takeIf { it.isNotBlank() },
                    )
                }
                body
            }
        }
    }

    companion object {
        private const val MQTT_HOST = "mwa.mpsa.com"
        private const val MQTT_PORT = 8885
        private const val MQTT_USER = "IMA_OAUTH_ACCESS_TOKEN"
        private const val REQ_TOPIC = "psa/RemoteServices/from/cid/"
        private const val RESP_TOPIC = "psa/RemoteServices/to/cid/"
        private val IN_PROGRESS = setOf("900", "901", "903")
        private val TOKEN_MUTEX = Mutex()

        private const val K_OTP = "remote_otp"
        private const val K_CUSTOMER = "remote_customer"
        private const val K_MQTT_ACCESS = "mqtt_access"
        private const val K_MQTT_REFRESH = "mqtt_refresh"
        private const val K_MQTT_EXPIRES = "mqtt_expires"
        private const val K_MQTT_REFRESH_EXPIRES = "mqtt_refresh_expires"

        /** Vorklimatisierungs-Programme aus dem Status übernehmen (sonst würden sie überschrieben). */
        fun preconditioningPrograms(status: JSONObject?): JSONObject {
            val programs = JSONObject()
            for (i in 1..4) programs.put("program$i", JSONObject().put("day", JSONArray(List(7) { 0 })).put("hour", 34).put("minute", 7).put("on", 0))
            val list = status.pathObj("preconditioning", "airConditioning")?.optJSONArray("programs")
                ?: status.pathObj("preconditionning", "airConditioning")?.optJSONArray("programs")
            list.objects().forEach { p ->
                val days = p.optJSONObject("occurence")?.optJSONArray("day").strings()
                val (h, m) = ptTime(p.optString("start")) ?: return@forEach
                val slot = p.optInt("slot", 0).takeIf { it in 1..4 } ?: return@forEach
                if (days.isEmpty()) return@forEach
                programs.put(
                    "program$slot",
                    JSONObject()
                        .put("day", JSONArray(listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun").map { if (it in days) 1 else 0 }))
                        .put("hour", h).put("minute", m)
                        .put("on", if (p.optBoolean("enabled")) 1 else 0)
                )
            }
            return programs
        }

        /** "PT22H30M" → (22, 30) */
        fun ptTime(s: String?): Pair<Int, Int>? {
            if (s.isNullOrBlank() || !s.startsWith("PT")) return null
            val h = Regex("(\\d+)H").find(s)?.groupValues?.get(1)?.toInt() ?: 0
            val m = Regex("(\\d+)M").find(s)?.groupValues?.get(1)?.toInt() ?: 0
            return h to m
        }
    }
}
