package com.controltorbox.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Versión publicada en GitHub (release «android-latest»). */
data class UpdateInfo(val versionCode: Int, val versionName: String, val apkUrl: String)

/** Busca, descarga e instala nuevas versiones de la app desde GitHub. */
class Updater(
    private val versionUrl: String = VERSION_URL,
    private val http: OkHttpClient = defaultClient,
) {
    /** Devuelve la versión publicada, o lanza [TorboxException] si no se puede consultar. */
    suspend fun latest(): UpdateInfo = withContext(Dispatchers.IO) {
        val body = try {
            http.newCall(Request.Builder().url(versionUrl).header("Cache-Control", "no-cache").build()).execute().use {
                if (!it.isSuccessful) throw TorboxException("No se pudo consultar la última versión (HTTP ${it.code})")
                it.body?.string().orEmpty()
            }
        } catch (e: IOException) {
            throw TorboxException("No se pudo consultar la última versión: ${e.message}")
        }
        parse(body, versionUrl)
    }

    /** Hay actualización si la versión publicada es mayor que la instalada. */
    suspend fun check(currentCode: Int): UpdateInfo? = latest().takeIf { it.versionCode > currentCode }

    /** Descarga el APK a la caché de la app. */
    suspend fun download(context: Context, info: UpdateInfo, onProgress: (Float) -> Unit = {}): File =
        withContext(Dispatchers.IO) {
            val dir = File(context.cacheDir, "updates").apply { mkdirs() }
            dir.listFiles()?.forEach { it.delete() }
            val out = File(dir, "Controltorbox-${info.versionCode}.apk")
            try {
                http.newCall(Request.Builder().url(info.apkUrl).build()).execute().use { resp ->
                    if (!resp.isSuccessful) throw TorboxException("No se pudo descargar la actualización (HTTP ${resp.code})")
                    val body = resp.body ?: throw TorboxException("Descarga vacía")
                    val total = body.contentLength()
                    body.byteStream().use { input ->
                        out.outputStream().use { output ->
                            val buf = ByteArray(64 * 1024)
                            var done = 0L
                            while (true) {
                                val n = input.read(buf)
                                if (n < 0) break
                                output.write(buf, 0, n)
                                done += n
                                if (total > 0) onProgress(done.toFloat() / total)
                            }
                        }
                    }
                }
            } catch (e: IOException) {
                out.delete()
                throw TorboxException("No se pudo descargar la actualización: ${e.message}")
            }
            out
        }

    companion object {
        const val RELEASE_BASE = "https://github.com/fcojalvarez/Controltorbox/releases/download/android-latest/"
        const val VERSION_URL = RELEASE_BASE + "version.json"

        private val defaultClient = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()

        fun parse(json: String, baseUrl: String): UpdateInfo {
            val o = try {
                JSONObject(json)
            } catch (e: Exception) {
                throw TorboxException("Información de versión no válida")
            }
            val code = o.optInt("versionCode", -1)
            if (code < 0) throw TorboxException("Información de versión no válida")
            val apk = o.optString("apk", "Controltorbox.apk")
            val apkUrl = if (apk.startsWith("http")) apk else baseUrl.substringBeforeLast('/') + "/" + apk
            return UpdateInfo(code, o.optString("versionName", code.toString()), apkUrl)
        }

        /** ¿Permite Android que esta app instale APKs? (Android 8+ lo pide una vez). */
        fun canInstall(context: Context): Boolean =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.packageManager.canRequestPackageInstalls()

        /** Abre el ajuste de Android para permitir instalar actualizaciones desde esta app. */
        fun openInstallPermission(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startActivity(
                    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
        }

        /** Lanza el instalador de Android con el APK descargado. */
        fun install(context: Context, apk: File) {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", apk)
            context.startActivity(
                Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }
}
