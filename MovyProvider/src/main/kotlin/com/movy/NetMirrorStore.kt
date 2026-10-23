package com.movy

import android.content.Context
import android.content.SharedPreferences
import android.os.Environment
import org.json.JSONObject
import java.io.File

object NetMirrorStore {
    private const val PREFS = "netmirror_session"
    private const val KEY = "cookies"
    private const val KEY_TIMESTAMP = "file_timestamp"
    private const val SHARED_FILE_NAME = ".netmirror_session"

    @Volatile
    private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
        // Attempt initial sync from shared file if local prefs are empty or file is newer
        syncFromSharedFileIfNeeded()
    }

    private fun prefs(): SharedPreferences? =
        appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun sharedFile(): File? = try {
        val csDir = File("/storage/emulated/0/Cloudstream3")
        if (!csDir.exists()) csDir.mkdirs()
        File(csDir, SHARED_FILE_NAME)
    } catch (t: Throwable) {
        try {
            val download = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            File(download, SHARED_FILE_NAME)
        } catch (e: Throwable) {
            null
        }
    }

    private fun syncFromSharedFileIfNeeded() {
        val file = sharedFile() ?: return
        if (!file.exists() || !file.canRead()) return

        val fileMod = file.lastModified()
        val localMod = prefs()?.getLong(KEY_TIMESTAMP, 0L) ?: 0L
        val current = prefs()?.getString(KEY, null)

        if (current.isNullOrBlank() || fileMod > localMod) {
            runCatching {
                val text = file.readText(Charsets.UTF_8).trim()
                if (text.isNotBlank() && text.startsWith("{")) {
                    prefs()?.edit()
                        ?.putString(KEY, text)
                        ?.putLong(KEY_TIMESTAMP, fileMod)
                        ?.apply()
                }
            }
        }
    }

    private fun saveToSharedFile(jsonStr: String) {
        try {
            val file = sharedFile() ?: return
            file.writeText(jsonStr, Charsets.UTF_8)
            prefs()?.edit()?.putLong(KEY_TIMESTAMP, file.lastModified())?.apply()
        } catch (t: Throwable) {
            // Non-critical if external storage permission is restricted
        }
    }

    fun cookies(): MutableMap<String, String> {
        syncFromSharedFileIfNeeded()
        val raw = prefs()?.getString(KEY, null)
        if (raw.isNullOrBlank()) return mutableMapOf()

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
        val str = json.toString()
        prefs()?.edit()?.putString(KEY, str)?.apply()
        saveToSharedFile(str)
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
        prefs()?.edit()?.remove(KEY)?.remove(KEY_TIMESTAMP)?.apply()
        try {
            sharedFile()?.delete()
        } catch (t: Throwable) {}
    }

    fun userToken(): String? = cookies()["user_token"]?.takeIf { it.isNotBlank() }

    fun header(): String = cookies().entries.joinToString("; ") { "${it.key}=${it.value}" }
}
