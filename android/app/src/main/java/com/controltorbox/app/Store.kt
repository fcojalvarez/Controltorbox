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
        get() = prefs.getString("sort", "date_desc") ?: "date_desc"
        set(value) = prefs.edit().putString("sort", value).apply()

    var alertSettings: AlertSettings
        get() = AlertSettings(
            notifyNoSpanish = prefs.getBoolean("notify_no_es", true),
            notifyDuplicates = prefs.getBoolean("notify_dups", true),
            notifyStorage = prefs.getBoolean("notify_storage", true),
            storageLimitGb = prefs.getInt("storage_limit_gb", 500),
            notifyUpdates = prefs.getBoolean("notify_updates", true),
        )
        set(s) = prefs.edit()
            .putBoolean("notify_no_es", s.notifyNoSpanish)
            .putBoolean("notify_dups", s.notifyDuplicates)
            .putBoolean("notify_storage", s.notifyStorage)
            .putInt("storage_limit_gb", s.storageLimitGb)
            .putBoolean("notify_updates", s.notifyUpdates)
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

    /** Última versión nueva encontrada (si la hay) y la última de la que ya se avisó. */
    var availableUpdate: UpdateInfo?
        get() {
            val code = prefs.getInt("update_code", -1)
            if (code <= BuildConfig.VERSION_CODE) return null
            return UpdateInfo(code, prefs.getString("update_name", "") ?: "", prefs.getString("update_url", "") ?: "")
        }
        set(u) {
            val e = prefs.edit()
            if (u == null) e.remove("update_code").remove("update_name").remove("update_url")
            else e.putInt("update_code", u.versionCode).putString("update_name", u.versionName).putString("update_url", u.apkUrl)
            e.apply()
        }

    var notifiedUpdateCode: Int
        get() = prefs.getInt("notified_update_code", -1)
        set(v) = prefs.edit().putInt("notified_update_code", v).apply()

    /** Elementos que decidiste conservar al revisar: no vuelven a salir en «Para limpiar». */
    var kept: Set<String>
        get() = prefs.getStringSet("kept", emptySet())?.toSet() ?: emptySet()
        set(v) = prefs.edit().putStringSet("kept", v).apply()

    /** Plan leído de TorBox y límite de almacenamiento (detectado o puesto a mano, en GB; 0 = sin definir). */
    var accountPlan: Int?
        get() = prefs.getInt("account_plan", -1).takeIf { it >= 0 }
        set(v) = prefs.edit().putInt("account_plan", v ?: -1).apply()

    var accountLimitDetected: Long?
        get() = prefs.getLong("account_limit_detected", 0).takeIf { it > 0 }
        set(v) = prefs.edit().putLong("account_limit_detected", v ?: 0).apply()

    var accountLimitManualGb: Int
        get() = prefs.getInt("account_limit_manual_gb", 0)
        set(v) = prefs.edit().putInt("account_limit_manual_gb", v).apply()

    var lastCheck: Long
        get() = prefs.getLong("last_check", 0)
        set(v) = prefs.edit().putLong("last_check", v).apply()

    // -- Análisis de audio y marcas manuales ---------------------------------
    private fun readObj(key: String) = try {
        JSONObject(prefs.getString(key, "{}") ?: "{}")
    } catch (e: Exception) {
        JSONObject()
    }

    private fun tracksFrom(arr: JSONArray?): List<AudioTrack> {
        arr ?: return emptyList()
        return (0 until arr.length()).map { i ->
            val t = arr.getJSONObject(i)
            AudioTrack(
                t.getInt("index"), t.optString("language"), t.optString("label"),
                t.optString("codec"), t.optInt("channels"), t.optBoolean("supported", true),
            )
        }
    }

    private fun tracksJson(tracks: List<AudioTrack>) = JSONArray().apply {
        tracks.forEach { t ->
            put(
                JSONObject().put("index", t.index).put("language", t.language).put("label", t.label)
                    .put("codec", t.codec).put("channels", t.channels).put("supported", t.supported),
            )
        }
    }

    private fun strings(arr: JSONArray?) = arr?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList()

    fun analyses(): Map<String, Analysis> {
        val o = readObj("analyses")
        return o.keys().asSequence().mapNotNull { k ->
            try {
                val a = o.getJSONObject(k)
                val eps = a.optJSONArray("episodes")?.let { arr ->
                    (0 until arr.length()).map { i ->
                        val e = arr.getJSONObject(i)
                        EpisodeAnalysis(
                            e.getLong("fileId"), e.optString("name"), AudioStatus.valueOf(e.getString("status")),
                            strings(e.optJSONArray("languages")), tracksFrom(e.optJSONArray("tracks")),
                            e.optString("error").takeIf { it.isNotBlank() },
                        )
                    }
                } ?: emptyList()
                k to Analysis(
                    AudioStatus.valueOf(a.getString("status")), strings(a.optJSONArray("languages")),
                    tracksFrom(a.optJSONArray("tracks")), eps,
                )
            } catch (e: Exception) {
                null
            }
        }.toMap()
    }

    fun saveAnalysis(key: String, a: Analysis) {
        val o = readObj("analyses")
        val json = JSONObject().put("status", a.status.name).put("languages", JSONArray(a.languages)).put("tracks", tracksJson(a.tracks))
        if (a.episodes.isNotEmpty()) {
            json.put("episodes", JSONArray().apply {
                a.episodes.forEach { e ->
                    put(
                        JSONObject().put("fileId", e.fileId).put("name", e.name).put("status", e.status.name)
                            .put("languages", JSONArray(e.languages)).put("tracks", tracksJson(e.tracks))
                            .put("error", e.error ?: ""),
                    )
                }
            })
        }
        o.put(key, json)
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
        prefs.edit().putString("analyses", a.toString()).putString("manual", m.toString())
            .putStringSet("kept", kept - keys.toSet()).apply()
    }

    /** Estado del audio: marca manual > análisis > nombre. */
    fun statusOf(item: TorboxItem, analyses: Map<String, Analysis> = analyses(), manual: Map<String, AudioStatus> = manualMarks()):
        Pair<AudioStatus, AudioSource> {
        manual[item.key]?.let { return it to AudioSource.MANUAL }
        analyses[item.key]?.let { return it.status to AudioSource.ANALYZED }
        return Language.guessFromName(item.name, item.mainVideo?.displayName) to AudioSource.NAME
    }
}
