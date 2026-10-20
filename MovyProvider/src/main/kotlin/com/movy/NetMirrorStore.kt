package com.movy

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject

object NetMirrorStore {
    private const val PREFS = "netmirror_session"
    private const val KEY = "cookies"

    @Volatile
    private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    private fun prefs(): SharedPreferences? =
        appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun cookies(): MutableMap<String, String> {
        val raw = prefs()?.getString(KEY, null) ?: return mutableMapOf()
        return runCatching {
            val json = JSONObject(raw)
            val result = mutableMapOf<String, String>()
            json.keys().forEach { key ->
                val value = json.optString(key)
                if (value.isNotBlank()) result[key] = value
            }
            result
        }.getOrElse { mutableMapOf() }
    }

    fun update(values: Map<String, String>) {
        val merged = cookies()
        values.forEach { (key, value) ->
            if (value.isNotBlank()) merged[key] = value
        }
        save(merged)
    }

    fun save(values: Map<String, String>) {
        val json = JSONObject()
        values.forEach { (key, value) ->
            if (value.isNotBlank()) json.put(key, value)
        }
        prefs()?.edit()?.putString(KEY, json.toString())?.apply()
    }

    fun saveCookieHeader(header: String?) {
        if (header.isNullOrBlank()) return
        val values = header.replace('\n', ';').split(";").mapNotNull { part ->
            val index = part.indexOf('=')
            if (index <= 0) null else {
                val key = part.substring(0, index).trim()
                val value = part.substring(index + 1).trim()
                if (key.isBlank() || value.isBlank()) null else key to value
            }
        }.toMap()
        update(values)
    }

    fun saveUserToken(value: String) {
        var trimmed = value.trim()
        if (trimmed.startsWith("Cookie:", ignoreCase = true)) {
            trimmed = trimmed.substringAfter(':').trim()
        }
        if (trimmed.isBlank()) return
        val cookieLike = Regex(
            """(?i)(^|;)\s*(user_token|t_hash_p|cf_clearance|t_hash_t)\s*="""
        ).containsMatchIn(trimmed)
        if (cookieLike) {
            saveCookieHeader(trimmed)
        } else {
            update(mapOf("user_token" to trimmed))
        }
    }

    fun clear() {
        prefs()?.edit()?.remove(KEY)?.apply()
    }

    fun userToken(): String? = cookies()["user_token"]?.takeIf { it.isNotBlank() }

    fun header(): String = cookies().entries.joinToString("; ") { "${it.key}=${it.value}" }
}
