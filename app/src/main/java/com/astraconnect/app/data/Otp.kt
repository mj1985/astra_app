package com.astraconnect.app.data

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader
import java.math.BigInteger
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

class OtpException(message: String) : Exception(message)

/**
 * Software-OTP-Generator, wie ihn die MyOpel-App für Fernbefehle nutzt (Dienst otp.mpsa.com).
 *
 * Ablauf:
 *  1. Einmalige Aktivierung mit SMS-Code + PIN der MyOpel-App → Schlüsselmaterial wird hier gespeichert.
 *  2. Für jedes neue MQTT-Token wird damit ein Einmal-Code erzeugt (Stellantis erlaubt nur wenige pro Tag).
 *
 * Portiert aus den Open-Source-Projekten psa_car_controller / homeassistant-stellantis-vehicles.
 * Der Zustand ändert sich bei jeder Code-Erzeugung und muss danach wieder gespeichert werden.
 */
class Otp private constructor() {
    private var deviceId = ""
    private var iwalea = ""
    private var codepin = ""
    private var smsCode = ""
    private var kfact = ""
    private var kiw = ""
    private var iwid = ""
    private var iwTsync = "0"
    private var iwK0 = ""
    private var iwK1 = ""
    private var iwsecid = ""
    private var iwsecval = ""
    private var action = ""
    private var challenge = ""
    private var defi = ""

    private val serial get() = "$deviceId/_/$iwalea"

    // ---------------------------------------------------------------- öffentliche API

    /** Einmalige Aktivierung mit SMS-Code und MyOpel-PIN. */
    fun activate(http: OkHttpClient, sms: String, pin: String) {
        smsCode = sms.trim()
        codepin = pin.trim()
        start(http, MODE_ACTIVATE)
        val r = finalize(http, MODE_ACTIVATE)
        if (r != OK) throw OtpException("Aktivierung abgelehnt ($r). SMS-Code oder PIN falsch?")
    }

    /** Erzeugt einen neuen Einmal-Code. Danach [toJson] speichern! */
    fun nextCode(http: OkHttpClient): String {
        start(http, MODE_OTP)
        var r = finalize(http, MODE_OTP)
        if (r == TWICE) {
            start(http, MODE_OTP)
            r = finalize(http, MODE_OTP)
        }
        if (r != OK) throw OtpException("OTP-Code konnte nicht erzeugt werden ($r).")
        val digest = sha256("$iwK1:$defi:$iwsecval".toByteArray())
        val a = BigInteger(1, digest.copyOfRange(0, 4)).toLong() and 0xfffffffL
        val b = BigInteger(1, digest.copyOfRange(4, 8)).toLong() and 1023L
        return base36(a * 1024 + b)
    }

    fun toJson(): String = JSONObject().apply {
        put("deviceId", deviceId); put("iwalea", iwalea); put("codepin", codepin)
        put("kfact", kfact); put("kiw", kiw); put("iwid", iwid); put("iwTsync", iwTsync)
        put("iwK0", iwK0); put("iwK1", iwK1); put("iwsecid", iwsecid); put("iwsecval", iwsecval)
        put("action", action); put("challenge", challenge); put("defi", defi)
    }.toString()

    // ---------------------------------------------------------------- Protokoll

    private fun start(http: OkHttpClient, mode: String) {
        val p = linkedMapOf(
            "action" to "ActionSetup", "mode" to mode, "id" to iwid, "lastsync" to iwTsync,
            "version" to VERSION, "macid" to MAC_ID,
        )
        if (mode == MODE_OTP) p["sid"] = iwsecid else p["code"] = smsCode
        val xml = request(http, p, "ActionSetup")
        if (xml["err"] != "OK") throw OtpException("OTP-Server: ${xml["err"] ?: "unbekannter Fehler"}")
        if (mode == MODE_ACTIVATE) {
            kfact = xml["Kfact"] ?: throw OtpException("Kfact fehlt")
            kiw = decodeOaep(xml["Kiw"] ?: throw OtpException("Kiw fehlt"), kfact)
        } else {
            challenge = xml["challenge"].orEmpty()
        }
    }

    private fun finalize(http: OkHttpClient, mode: String): String {
        val p = linkedMapOf(
            "action" to "ActionFinalize", "mode" to mode, "id" to iwid, "lastsync" to iwTsync,
            "version" to VERSION, "lang" to "fr", "ack" to "", "macid" to MAC_ID,
        )
        if (mode == MODE_OTP) {
            p["keytype"] = "0"
            p["sid"] = iwsecid
        } else {
            p["serial"] = serial
            p["code"] = smsCode
            p["Kma"] = hex(oaepEncrypt(unhex(kma()), kiw))
            p["pin"] = hex(oaepEncrypt(codepin.toByteArray(), kiw))
            p["name"] = "Android SDK built for x86_64 / UNKNOWN"
        }
        p.putAll(responses())
        val xml = request(http, p, "ActionFinalize")
        val err = xml["err"]
        if (err != "OK") return err ?: "Fehler"
        synchro(xml)

        if (mode == MODE_OTP) {
            defi = xml["defi"] ?: throw OtpException("defi fehlt in OTP-Antwort")
            return if (xml.containsKey("J")) TWICE else OK
        }

        val msN = xml["ms_n"]
        if (msN == null || msN == "0") return OK
        if ((msN.toIntOrNull() ?: 0) > 1) throw OtpException("Nicht unterstützte Aktivierungsvariante (ms_n=$msN)")

        // Zweiter Schritt: Sicherheitsschlüssel austauschen
        challenge = xml["challenge"].orEmpty()
        action = "synchro"
        val tempModulus = decodeOaep(xml["ms_key"] ?: throw OtpException("ms_key fehlt"), kfact)
        val random = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val kpub = oaepEncrypt(random, tempModulus)
        iwsecval = hex(aes(Cipher.ENCRYPT_MODE, unhex(kma()), random))
        iwsecid = xml["s_id"].orEmpty()

        val p2 = linkedMapOf(
            "action" to "ActionFinalize", "mode" to MODE_MS,
            "ms_id0" to xml["ms_id"].orEmpty(), "ms_val0" to hex(kpub), "macid" to MAC_ID,
            "id" to iwid, "lastsync" to iwTsync, "ms_n" to "1",
        )
        p2.putAll(responses())
        val xml2 = request(http, p2, "ActionFinalize")
        if (xml2["err"] != null && xml2["err"] != "OK") return xml2["err"]!!
        synchro(xml2)
        return OK
    }

    private fun responses(): Map<String, String> {
        val iw = iwK0
        val r2 = if (action == "synchro") "$challenge;$iw;$codepin" else "$challenge;$iw;"
        return mapOf(
            "R0" to sha256Hex("$challenge;$iw;$serial"),
            "R1" to sha256Hex("$challenge;$iw;$iwK1"),
            "R2" to sha256Hex(r2),
        )
    }

    private fun kma() = sha256Hex("$codepin;$serial").substring(0, 32)

    private fun synchro(xml: Map<String, String>) {
        val key = unhex(kma())
        xml["id"]?.takeIf { it.isNotEmpty() }?.let { iwid = it }
        xml["K0"]?.takeIf { it.isNotEmpty() }?.let { iwK0 = hex(aes(Cipher.DECRYPT_MODE, key, unhex(it))) }
        xml["K1"]?.takeIf { it.isNotEmpty() }?.let { iwK1 = hex(aes(Cipher.DECRYPT_MODE, key, unhex(it))) }
        xml["dK1"]?.takeIf { it.isNotEmpty() }?.let { iwK1 = sha256Hex("$iwK1;$it").substring(0, 32) }
        if (!xml["s_n"].isNullOrEmpty()) iwTsync = xml["Tsync"].orEmpty()
    }

    private fun request(http: OkHttpClient, params: Map<String, String>, root: String): Map<String, String> {
        val url = HOST.toHttpUrl().newBuilder().apply { params.forEach { (k, v) -> addQueryParameter(k, v) } }.build()
        val req = Request.Builder().url(url)
            .header("User-Agent", "Dalvik/2.1.0 (Linux; U; Android 8.0.0; Android SDK built for x86_64 Build/OSR1.180418.004)")
            .build()
        val body = http.newCall(req).execute().use { it.body?.string().orEmpty() }
        return parseXml(body, root)
    }

    // ---------------------------------------------------------------- Hilfsfunktionen

    companion object {
        private const val HOST = "https://otp.mpsa.com/iwws/MAC"
        private const val MAC_ID = "bb8e981582b0f31353108fb020bead1c"
        private const val VERSION = "Generator-1.0/0.2.11"
        private const val MODE_ACTIVATE = "activate"
        private const val MODE_OTP = "otp"
        private const val MODE_MS = "ms"
        private const val OK = "OK"
        private const val TWICE = "TWICE"
        private val E = BigInteger.valueOf(0x11)
        private const val H_LEN = 32
        private val rnd = SecureRandom()

        fun newSession(): Otp = Otp().apply {
            deviceId = hex(ByteArray(8).also { rnd.nextBytes(it) })
            iwalea = hex(ByteArray(16).also { rnd.nextBytes(it) })
        }

        fun fromJson(s: String): Otp = JSONObject(s).let { j ->
            Otp().apply {
                deviceId = j.optString("deviceId"); iwalea = j.optString("iwalea"); codepin = j.optString("codepin")
                kfact = j.optString("kfact"); kiw = j.optString("kiw"); iwid = j.optString("iwid")
                iwTsync = j.optString("iwTsync", "0"); iwK0 = j.optString("iwK0"); iwK1 = j.optString("iwK1")
                iwsecid = j.optString("iwsecid"); iwsecval = j.optString("iwsecval"); action = j.optString("action")
                challenge = j.optString("challenge"); defi = j.optString("defi")
            }
        }

        internal fun parseXml(raw: String, root: String): Map<String, String> {
            val start = raw.indexOf("?>").let { if (it >= 0) it + 2 else 0 }
            val parser = XmlPullParserFactory.newInstance().newPullParser()
            parser.setInput(StringReader(raw.substring(start)))
            val result = linkedMapOf<String, String>()
            var depth = 0
            var rootName: String? = null
            var current: String? = null
            while (parser.next() != XmlPullParser.END_DOCUMENT) {
                when (parser.eventType) {
                    XmlPullParser.START_TAG -> {
                        depth++
                        if (depth == 1) rootName = parser.name else if (depth == 2) current = parser.name
                    }
                    XmlPullParser.TEXT -> current?.let { if (depth == 2 && it !in result) result[it] = parser.text.trim() }
                    XmlPullParser.END_TAG -> {
                        if (depth == 2) { current?.let { result.putIfAbsent(it, "") }; current = null }
                        depth--
                    }
                }
            }
            if (rootName != root) throw OtpException("Unerwartete Antwort vom OTP-Server")
            return result
        }

        /** RSA-"Entschlüsselung" mit öffentlichem Schlüssel + OAEP(SHA-256), blockweise à 128 Byte. */
        internal fun decodeOaep(encHex: String, modulusHex: String): String {
            val n = BigInteger(modulusHex, 16)
            val enc = unhex(encHex)
            val sb = StringBuilder()
            var off = 0
            while (off < enc.size) {
                val end = minOf(off + 128, enc.size)
                sb.append(hex(oaepUnpad(enc.copyOfRange(off, end), n)))
                off = end
            }
            return sb.toString()
        }

        private fun oaepUnpad(c: ByteArray, n: BigInteger): ByteArray {
            val k = (n.bitLength() + 7) / 8
            if (c.size != k) throw OtpException("Ungültige Blocklänge")
            val em = fixed(BigInteger(1, c).modPow(E, n), k)
            val maskedSeed = em.copyOfRange(1, H_LEN + 1)
            val maskedDb = em.copyOfRange(H_LEN + 1, k)
            val seed = xor(maskedSeed, mgf1(maskedDb, H_LEN))
            val db = xor(maskedDb, mgf1(seed, k - H_LEN - 1))
            val lHash = sha256(ByteArray(0))
            if (!db.copyOfRange(0, H_LEN).contentEquals(lHash) || em[0].toInt() != 0) throw OtpException("Entschlüsselung fehlgeschlagen")
            var i = H_LEN
            while (i < db.size && db[i].toInt() == 0) i++
            if (i >= db.size || db[i].toInt() != 1) throw OtpException("Entschlüsselung fehlgeschlagen")
            return db.copyOfRange(i + 1, db.size)
        }

        /** Standard RSA-OAEP mit SHA-256 und MGF1-SHA-256. */
        internal fun oaepEncrypt(msg: ByteArray, modulusHex: String): ByteArray {
            val n = BigInteger(modulusHex, 16)
            val k = (n.bitLength() + 7) / 8
            require(msg.size <= k - 2 * H_LEN - 2) { "Nachricht zu lang" }
            val db = ByteArray(k - H_LEN - 1)
            System.arraycopy(sha256(ByteArray(0)), 0, db, 0, H_LEN)
            db[db.size - msg.size - 1] = 1
            System.arraycopy(msg, 0, db, db.size - msg.size, msg.size)
            val seed = ByteArray(H_LEN).also { rnd.nextBytes(it) }
            val maskedDb = xor(db, mgf1(seed, db.size))
            val maskedSeed = xor(seed, mgf1(maskedDb, H_LEN))
            val em = ByteArray(k)
            System.arraycopy(maskedSeed, 0, em, 1, H_LEN)
            System.arraycopy(maskedDb, 0, em, H_LEN + 1, maskedDb.size)
            return fixed(BigInteger(1, em).modPow(E, n), k)
        }

        private fun mgf1(seed: ByteArray, len: Int): ByteArray {
            val out = java.io.ByteArrayOutputStream()
            var counter = 0
            while (out.size() < len) {
                val c = byteArrayOf((counter ushr 24).toByte(), (counter ushr 16).toByte(), (counter ushr 8).toByte(), counter.toByte())
                out.write(sha256(seed + c))
                counter++
            }
            return out.toByteArray().copyOf(len)
        }

        private fun fixed(v: BigInteger, k: Int): ByteArray {
            val b = v.toByteArray()
            return when {
                b.size == k -> b
                b.size > k -> b.copyOfRange(b.size - k, b.size)
                else -> ByteArray(k - b.size) + b
            }
        }

        private fun aes(mode: Int, key: ByteArray, data: ByteArray): ByteArray =
            Cipher.getInstance("AES/ECB/NoPadding").run {
                init(mode, SecretKeySpec(key, "AES"))
                doFinal(data)
            }

        private fun xor(a: ByteArray, b: ByteArray) = ByteArray(a.size) { (a[it].toInt() xor b[it].toInt()).toByte() }
        private fun sha256(b: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(b)
        private fun sha256Hex(s: String) = hex(sha256(s.toByteArray(Charsets.UTF_8)))
        internal fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
        internal fun unhex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

        private const val ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789"

        /** Wie im Original: Ziffern in umgekehrter Reihenfolge (niederwertigste zuerst). */
        internal fun base36(value: Long): String {
            if (value == 0L) return "0"
            var n = value
            val sb = StringBuilder()
            while (n > 0) {
                sb.append(ALPHABET[(n % 36).toInt()])
                n /= 36
            }
            return sb.toString()
        }
    }
}
