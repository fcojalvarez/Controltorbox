package com.controltorbox.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Datos guardados en el móvil (privados de la app). */
class Store(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("controltorbox", Context.MODE_PRIVATE)

    var token: String
        get() = prefs.getString("token", "") ?: ""
        set(value) = prefs.edit().putString("token", value.trim()).apply()

    var sort: String
        get() = prefs.getString("sort", "size_desc") ?: "size_desc"
        set(value) = prefs.edit().putString("sort", value).apply()

    var alertSettings: AlertSettings
        get() = AlertSettings(
            notifyNoSpanish = prefs.getBoolean("notify_no_es", true),
            notifyDuplicates = prefs.getBoolean("notify_dups", true),
            notifyStorage = prefs.getBoolean("notify_storage", true),
            storageLimitGb = prefs.getInt("storage_limit_gb", 500),
            intervalMinutes = prefs.getInt("interval_min", 60),
        )
        set(s) = prefs.edit()
            .putBoolean("notify_no_es", s.notifyNoSpanish)
            .putBoolean("notify_dups", s.notifyDuplicates)
            .putBoolean("notify_storage", s.notifyStorage)
            .putInt("storage_limit_gb", s.storageLimitGb)
            .putInt("interval_min", s.intervalMinutes)
            .apply()

    var monitorState: MonitorState?
        get() {
            val keys = prefs.getStringSet("known_keys", null) ?: return null
            return MonitorState(keys.toSet(), prefs.getBoolean("storage_alerted", false))
        }
        set(s) {
            val e = prefs.edit()
            if (s == null) e.remove("known_keys").remove("storage_alerted")
            else e.putStringSet("known_keys", s.knownKeys).putBoolean("storage_alerted", s.storageAlerted)
            e.apply()
        }

    var lastCheck: Long
        get() = prefs.getLong("last_check", 0)
        set(v) = prefs.edit().putLong("last_check", v).apply()

    // -- Análisis de audio y marcas manuales ---------------------------------
    private fun readObj(key: String) = try {
        JSONObject(prefs.getString(key, "{}") ?: "{}")
    } catch (e: Exception) {
        JSONObject()
    }

    fun analyses(): Map<String, Analysis> {
        val o = readObj("analyses")
        return o.keys().asSequence().mapNotNull { k ->
            try {
                val a = o.getJSONObject(k)
                val tracksArr = a.optJSONArray("tracks") ?: JSONArray()
                val tracks = (0 until tracksArr.length()).map { i ->
                    val t = tracksArr.getJSONObject(i)
                    AudioTrack(
                        t.getInt("index"), t.optString("language"), t.optString("label"),
                        t.optString("codec"), t.optInt("channels"), t.optBoolean("supported", true),
                    )
                }
                val langs = a.optJSONArray("languages") ?: JSONArray()
                k to Analysis(
                    AudioStatus.valueOf(a.getString("status")),
                    (0 until langs.length()).map { langs.getString(it) },
                    tracks,
                )
            } catch (e: Exception) {
                null
            }
        }.toMap()
    }

    fun saveAnalysis(key: String, a: Analysis) {
        val o = readObj("analyses")
        val tracks = JSONArray()
        a.tracks.forEach { t ->
            tracks.put(
                JSONObject().put("index", t.index).put("language", t.language).put("label", t.label)
                    .put("codec", t.codec).put("channels", t.channels).put("supported", t.supported),
            )
        }
        o.put(key, JSONObject().put("status", a.status.name).put("languages", JSONArray(a.languages)).put("tracks", tracks))
        prefs.edit().putString("analyses", o.toString()).apply()
    }

    fun manualMarks(): Map<String, AudioStatus> {
        val o = readObj("manual")
        return o.keys().asSequence().mapNotNull { k ->
            runCatching { k to AudioStatus.valueOf(o.getString(k)) }.getOrNull()
        }.toMap()
    }

    fun setManual(key: String, status: AudioStatus?) {
        val o = readObj("manual")
        if (status == null) o.remove(key) else o.put(key, status.name)
        prefs.edit().putString("manual", o.toString()).apply()
    }

    fun forget(keys: Collection<String>) {
        val a = readObj("analyses"); val m = readObj("manual")
        keys.forEach { a.remove(it); m.remove(it) }
        prefs.edit().putString("analyses", a.toString()).putString("manual", m.toString()).apply()
    }

    /** Estado del audio: marca manual > análisis > nombre. */
    fun statusOf(item: TorboxItem, analyses: Map<String, Analysis> = analyses(), manual: Map<String, AudioStatus> = manualMarks()):
        Pair<AudioStatus, AudioSource> {
        manual[item.key]?.let { return it to AudioSource.MANUAL }
        analyses[item.key]?.let { return it.status to AudioSource.ANALYZED }
        return Language.guessFromName(item.name, item.mainVideo?.displayName) to AudioSource.NAME
    }
}
