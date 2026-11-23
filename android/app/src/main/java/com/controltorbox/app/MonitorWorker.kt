package com.controltorbox.app

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/** Comprueba TorBox en segundo plano y envía las notificaciones. */
class MonitorWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val store = Store(applicationContext)
        val settings = store.alertSettings
        var failed = false

        // 1) Nueva versión de la app (no necesita token).
        try {
            val update = Updater().check(BuildConfig.VERSION_CODE)
            store.availableUpdate = update
            if (update != null && settings.notifyUpdates && store.notifiedUpdateCode < update.versionCode) {
                Notifications.show(applicationContext, Alert.Update(update))
                store.notifiedUpdateCode = update.versionCode
            }
        } catch (e: TorboxException) {
            failed = true
        }

        // 2) TorBox: nuevos sin español, duplicados y espacio.
        if (store.token.isNotBlank()) {
            try {
                val items = TorboxApi(store.token).listAll().first
                val analyses = store.analyses()
                val manual = store.manualMarks()
                val (alerts, state) = Monitor.check(store.monitorState, items, settings) {
                    store.statusOf(it, analyses, manual).first
                }
                store.monitorState = state
                alerts.forEach { Notifications.show(applicationContext, it) }
            } catch (e: TorboxException) {
                failed = true
            }
        }
        store.lastCheck = System.currentTimeMillis()
        return if (failed && runAttemptCount < 2) Result.retry() else Result.success()
    }

    companion object {
        private const val PERIODIC = "controltorbox-monitor"
        private const val NOW = "controltorbox-monitor-now"
        private val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        /** Programa la comprobación diaria (todas las comprobaciones, una vez al día). */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<MonitorWorker>(1, TimeUnit.DAYS)
                .setConstraints(constraints)
                .setBackoffCriteria(androidx.work.BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, request)
        }

        fun runNow(context: Context) {
            val request = OneTimeWorkRequestBuilder<MonitorWorker>().setConstraints(constraints).build()
            WorkManager.getInstance(context).enqueueUniqueWork(NOW, ExistingWorkPolicy.REPLACE, request)
        }
    }
}

object Notifications {
    private const val CHANNEL = "avisos"

    fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL, "Avisos de TorBox", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Nuevos sin español, duplicados, caducidad, espacio ocupado y nuevas versiones"
            }
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    fun canNotify(context: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun show(context: Context, alert: Alert) {
        if (!canNotify(context)) return
        createChannel(context)
        val filter = when (alert) {
            is Alert.NoSpanish -> MainActivity.FILTER_NO_ES
            is Alert.Duplicate -> MainActivity.FILTER_DUPS
            is Alert.Update -> MainActivity.SHOW_UPDATE
            is Alert.Expiring -> MainActivity.FILTER_EXPIRING
            is Alert.Storage -> null
        }
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .apply { filter?.let { putExtra(MainActivity.EXTRA_FILTER, it) } }
        val pending = PendingIntent.getActivity(
            context, alert.id, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(alert.title)
            .setContentText(alert.text.lineSequence().first())
            .setStyle(NotificationCompat.BigTextStyle().bigText(alert.text))
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(alert.id, notification)
        } catch (e: SecurityException) {
            // Permiso retirado mientras tanto: no hacemos nada.
        }
    }
}
