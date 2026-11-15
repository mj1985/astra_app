package com.astraconnect.app.data

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.OffsetDateTime

fun JSONObject?.obj(key: String): JSONObject? = this?.optJSONObject(key)
fun JSONObject?.arr(key: String): JSONArray? = this?.optJSONArray(key)

fun JSONObject?.str(key: String): String? =
    if (this != null && has(key) && !isNull(key)) opt(key)?.toString() else null

fun JSONObject?.num(key: String): Double? =
    if (this != null && has(key) && !isNull(key)) when (val v = opt(key)) {
        is Number -> v.toDouble()
        is String -> v.toDoubleOrNull()
        else -> null
    } else null

fun JSONObject?.bool(key: String): Boolean? = when (val v = this?.opt(key)) {
    is Boolean -> v
    is String -> v.lowercase().toBooleanStrictOrNull()
    else -> null
}

/** Folgt einer Kette von Objekt-Schlüsseln. */
fun JSONObject?.path(vararg keys: String): Any? {
    var cur: Any? = this
    for (k in keys) {
        val o = cur as? JSONObject ?: return null
        if (!o.has(k) || o.isNull(k)) return null
        cur = o.opt(k)
    }
    return cur
}

fun JSONObject?.pathObj(vararg keys: String) = path(*keys) as? JSONObject
fun JSONObject?.pathStr(vararg keys: String) = path(*keys)?.toString()
fun JSONObject?.pathNum(vararg keys: String): Double? = when (val v = path(*keys)) {
    is Number -> v.toDouble()
    is String -> v.toDoubleOrNull()
    else -> null
}

fun JSONArray?.objects(): List<JSONObject> =
    if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }

fun JSONArray?.strings(): List<String> =
    if (this == null) emptyList() else (0 until length()).mapNotNull { opt(it)?.toString() }

fun List<JSONObject>.firstWith(key: String, value: String) =
    firstOrNull { it.str(key).equals(value, ignoreCase = true) }

/** Erster vorhandener Zahlenwert unter mehreren Pfaden ("a.b.c"). */
fun firstNum(o: JSONObject?, vararg paths: String): Double? =
    paths.firstNotNullOfOrNull { o.pathNum(*it.split('.').toTypedArray()) }

fun firstStr(o: JSONObject?, vararg paths: String): String? =
    paths.firstNotNullOfOrNull { o.pathStr(*it.split('.').toTypedArray())?.takeIf { s -> s.isNotBlank() } }

fun parseInstant(s: String?): Instant? {
    if (s.isNullOrBlank()) return null
    return runCatching { OffsetDateTime.parse(s).toInstant() }.getOrNull()
        ?: runCatching { Instant.parse(s) }.getOrNull()
}
