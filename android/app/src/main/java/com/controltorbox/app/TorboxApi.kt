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

    suspend fun delete(kind: Kind, id: Long): Unit = withContext(Dispatchers.IO) {
        // TorBox exige también "all" (false = solo este elemento).
        val body = JSONObject().put(kind.controlIdField, id).put("operation", "delete").put("all", false).toString()
        execute(Request.Builder().url(url(kind.controlPath)).post(body.toRequestBody(JSON)).build())
        Unit
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
                    )
                }
            }
            val state = o.optString("download_state")
            return TorboxItem(
                kind = kind,
                id = o.optLong("id"),
                name = o.optString("name").ifBlank { files.firstOrNull()?.displayName ?: "${kind.label} ${o.optLong("id")}" },
                size = o.optLong("size"),
                createdAt = o.optString("created_at").takeIf { it.isNotBlank() && it != "null" },
                hash = o.optString("hash").takeIf { it != "null" }.orEmpty(),
                downloadFinished = o.optBoolean("download_finished", false) ||
                    state in setOf("completed", "cached", "uploading", "seeding"),
                files = files,
            )
        }
    }
}
