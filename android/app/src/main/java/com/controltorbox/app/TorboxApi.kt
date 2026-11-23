package com.controltorbox.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

class TorboxException(message: String) : Exception(message)

/** Cliente de la API de TorBox (v1). Llama directamente a TorBox: no hace falta servidor. */
class TorboxApi(
    private val token: String,
    baseUrl: String = DEFAULT_BASE_URL,
    private val http: OkHttpClient = defaultClient,
) {
    private val base: HttpUrl = baseUrl.trimEnd('/').plus("/").toHttpUrl()

    init {
        if (token.isBlank()) throw TorboxException("Falta el API token de TorBox. Añádelo en Ajustes.")
    }

    private fun url(path: String) = base.resolve(path) ?: throw TorboxException("URL no válida: $path")

    private fun execute(request: Request): Any? {
        val resp = try {
            http.newCall(request.newBuilder().header("Authorization", "Bearer $token").build()).execute()
        } catch (e: IOException) {
            throw TorboxException("No se pudo contactar con TorBox: ${e.message}")
        }
        resp.use {
            val body = it.body?.string().orEmpty()
            val json = try {
                JSONObject(body)
            } catch (e: Exception) {
                throw TorboxException("Respuesta no válida de TorBox (HTTP ${it.code})")
            }
            if (!it.isSuccessful || !json.optBoolean("success", false)) {
                val detail = json.optString("detail").ifBlank { json.optString("error") }.ifBlank { body.take(200) }
                throw TorboxException("TorBox: $detail (HTTP ${it.code})")
            }
            return if (json.isNull("data")) null else json.get("data")
        }
    }

    suspend fun list(kind: Kind): List<TorboxItem> = withContext(Dispatchers.IO) {
        val u = url(kind.listPath).newBuilder().addQueryParameter("bypass_cache", "true").build()
        val data = execute(Request.Builder().url(u).get().build())
        val arr = data as? JSONArray ?: return@withContext emptyList()
        (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let { parseItem(kind, it) } }
    }

    /** Lista todo; si un tipo falla pero otros no, devuelve lo que haya y los errores. */
    suspend fun listAll(): Pair<List<TorboxItem>, List<String>> {
        val items = mutableListOf<TorboxItem>()
        val errors = mutableListOf<String>()
        for (k in Kind.entries) {
            try {
                items += list(k)
            } catch (e: TorboxException) {
                errors += "${k.label}: ${e.message}"
            }
        }
        if (items.isEmpty() && errors.isNotEmpty()) throw TorboxException(errors.joinToString("; "))
        return items to errors
    }

    suspend fun delete(kind: Kind, id: Long) = control(kind, id, "delete")

    /** Operaciones de TorBox sobre una descarga: delete, reannounce, pause, resume. */
    suspend fun control(kind: Kind, id: Long, operation: String): Unit = withContext(Dispatchers.IO) {
        // TorBox exige también "all" (false = solo este elemento).
        val body = JSONObject().put(kind.controlIdField, id).put("operation", operation).put("all", false).toString()
        execute(Request.Builder().url(url(kind.controlPath)).post(body.toRequestBody(JSON)).build())
        Unit
    }

    /** Cola de TorBox para un tipo de descarga. */
    suspend fun queued(kind: Kind): List<QueuedItem> = withContext(Dispatchers.IO) {
        val u = url("queued/getqueued").newBuilder()
            .addQueryParameter("type", kind.key).addQueryParameter("bypass_cache", "true").build()
        val arr = execute(Request.Builder().url(u).get().build()) as? JSONArray ?: return@withContext emptyList()
        (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let { parseQueued(kind, it) } }
    }

    /** Cola de los tres tipos, sin repetidos (por si TorBox devuelve la misma cola para cada tipo). */
    suspend fun queuedAll(): List<QueuedItem> =
        Kind.entries.flatMap { k -> runCatching { queued(k) }.getOrDefault(emptyList()) }.distinctBy { it.kind to it.id }

    /**
     * Iniciar («start») o borrar («delete») algo de la cola.
     *
     * La API no documenta el formato exacto. Se envía primero lo mismo que una CLI de TorBox
     * que funciona con la API real ({queued_id, operation}); si TorBox no lo acepta, se prueba
     * añadiendo el tipo, y para iniciar también «resume» (la CLI lo llama así).
     */
    suspend fun controlQueued(item: QueuedItem, operation: String): Unit = withContext(Dispatchers.IO) {
        val ops = if (operation == "start") listOf("start", "resume") else listOf(operation)
        val bodies = ops.flatMap { op ->
            listOf(
                JSONObject().put("queued_id", item.id).put("operation", op),
                JSONObject().put("queued_id", item.id).put("operation", op).put("type", item.kind.key),
            )
        }
        var last: TorboxException? = null
        for (b in bodies) {
            try {
                execute(Request.Builder().url(url("queued/controlqueued")).post(b.toString().toRequestBody(JSON)).build())
                return@withContext
            } catch (e: TorboxException) {
                last = e
            }
        }
        throw last ?: TorboxException("TorBox no aceptó la operación sobre la cola.")
    }

    /** Plan de la cuenta y, si TorBox lo indica, su límite de almacenamiento. */
    suspend fun account(): AccountInfo = withContext(Dispatchers.IO) {
        val u = url("user/me").newBuilder().addQueryParameter("settings", "false").build()
        val data = execute(Request.Builder().url(u).get().build()) as? JSONObject
            ?: throw TorboxException("TorBox no devolvió los datos de la cuenta.")
        parseAccount(data)
    }

    suspend fun downloadLink(kind: Kind, id: Long, fileId: Long): String = withContext(Dispatchers.IO) {
        val u = url(kind.requestDlPath).newBuilder()
            .addQueryParameter("token", token)
            .addQueryParameter(kind.requestDlIdParam, id.toString())
            .addQueryParameter("file_id", fileId.toString())
            .addQueryParameter("redirect", "false")
            .build()
        val link = execute(Request.Builder().url(u).get().build()) as? String
        if (link.isNullOrBlank()) throw TorboxException("TorBox no devolvió un enlace de descarga.")
        link
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://api.torbox.app/v1/api/"
        private val JSON = "application/json".toMediaType()
        private val defaultClient = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()

        /**
         * La documentación de TorBox no incluye un campo de almacenamiento máximo; por si la
         * respuesta real lo trae, se busca uno numérico cuyo nombre hable de almacenamiento y límite.
         */
        fun parseAccount(o: JSONObject): AccountInfo {
            val plan = if (o.has("plan") && !o.isNull("plan")) o.optInt("plan") else null
            var limit: Long? = null
            for (key in o.keys()) {
                val k = key.lowercase()
                val aboutStorage = "storage" in k || "space" in k || "quota" in k
                val isLimit = "limit" in k || "max" in k || "total" in k || "quota" in k
                if (aboutStorage && isLimit && "used" !in k) {
                    val v = o.optDouble(key)
                    if (!v.isNaN()) {
                        // En bytes, o en GB si el número es pequeño.
                        limit = if (v > 1e7) v.toLong() else (v * 1024 * 1024 * 1024).toLong()
                        break
                    }
                }
            }
            return AccountInfo(plan, limit?.takeIf { it > 0 })
        }

        fun parseItem(kind: Kind, o: JSONObject): TorboxItem {
            val filesArr = o.optJSONArray("files") ?: JSONArray()
            val files = (0 until filesArr.length()).mapNotNull { i ->
                filesArr.optJSONObject(i)?.let { f ->
                    TorboxFile(
                        id = f.optLong("id"),
                        name = f.optString("name"),
                        shortName = f.optString("short_name"),
                        size = f.optLong("size"),
                        mimetype = f.optString("mimetype").takeIf { it != "null" }.orEmpty(),
                    )
                }
            }
            val state = o.str("download_state", "downloadState")
            val rawProgress = o.num("progress")
            val hasFlag = o.has("download_finished") || o.has("downloadFinished")
            // Sin ningún dato de estado se da por terminado (no se inventan descargas en curso).
            val finished = o.bool("download_finished", "downloadFinished") ||
                state.lowercase() in setOf("completed", "cached", "uploading", "seeding") || (!hasFlag && state.isBlank())
            return TorboxItem(
                kind = kind,
                id = o.optLong("id"),
                name = o.optString("name").ifBlank { files.firstOrNull()?.displayName ?: "${kind.label} ${o.optLong("id")}" },
                size = o.optLong("size"),
                createdAt = o.optString("created_at").takeIf { it.isNotBlank() && it != "null" },
                hash = o.optString("hash").takeIf { it != "null" }.orEmpty(),
                downloadFinished = finished,
                files = files,
                state = state,
                // TorBox lo da de 0 a 1; por si acaso, se acepta también de 0 a 100.
                progress = rawProgress?.let { (if (it > 1.0) it / 100.0 else it).toFloat().coerceIn(0f, 1f) }
                    ?: if (finished) 1f else 0f,
                downloadSpeed = o.num("download_speed", "downloadSpeed")?.toLong() ?: 0,
                eta = o.num("eta")?.toLong()?.takeIf { it > 0 },
                seeds = o.num("seeds")?.toInt(),
                peers = o.num("peers")?.toInt(),
                availability = o.num("availability"),
                expiresAt = o.str("expires_at", "expiresAt").takeIf { it.isNotBlank() },
            )
        }

        /** Elemento de la cola de TorBox (aún no empezado). */
        fun parseQueued(kind: Kind, o: JSONObject): QueuedItem = QueuedItem(
            // Si el elemento dice su tipo, manda sobre el que se pidió.
            kind = Kind.entries.firstOrNull { it.key == o.str("type").lowercase() } ?: kind,
            id = o.optLong("id"),
            name = o.str("name").ifBlank { "${kind.label} en cola ${o.optLong("id")}" },
            createdAt = o.str("created_at", "createdAt").takeIf { it.isNotBlank() },
            hash = o.str("hash"),
        )

        private fun JSONObject.str(vararg keys: String): String =
            keys.firstNotNullOfOrNull { k -> optString(k).takeIf { has(k) && !isNull(k) && it != "null" } } ?: ""

        private fun JSONObject.num(vararg keys: String): Double? =
            keys.firstNotNullOfOrNull { k -> if (has(k) && !isNull(k)) optDouble(k).takeIf { !it.isNaN() } else null }

        private fun JSONObject.bool(vararg keys: String): Boolean = keys.any { k -> has(k) && optBoolean(k, false) }
    }
}
