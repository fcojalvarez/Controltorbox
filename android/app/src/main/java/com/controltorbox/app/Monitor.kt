package com.controltorbox.app

import java.time.Instant

/** Ajustes de los avisos. */
data class AlertSettings(
    val notifyNoSpanish: Boolean = true,
    val notifyDuplicates: Boolean = true,
    val notifyStorage: Boolean = true,
    val storageLimitGb: Int = 500,
    val notifyUpdates: Boolean = true,
    val notifyExpiring: Boolean = true,
)

/** Lo que recordamos entre comprobaciones. */
data class MonitorState(
    val knownKeys: Set<String>,
    val storageAlerted: Boolean,
    /** Elementos de los que ya se avisó que caducan (para no repetir). */
    val expiringAlerted: Set<String> = emptySet(),
)

sealed class Alert {
    abstract val id: Int
    abstract val title: String
    abstract val text: String

    data class NoSpanish(val items: List<TorboxItem>, val statuses: Map<String, AudioStatus> = emptyMap()) : Alert() {
        override val id = 1001
        override val title = if (items.size == 1) "Nuevo sin audio en español" else "${items.size} nuevos sin audio en español"
        override val text = items.joinToString("\n") {
            val why = if (statuses[it.key] == AudioStatus.NO_ES) "sin español" else "no indica español"
            "• ${it.name} ($why)"
        }
    }

    data class Duplicate(val pairs: List<Pair<TorboxItem, String>>) : Alert() {
        override val id = 1002
        override val title = if (pairs.size == 1) "Has añadido un duplicado" else "Has añadido ${pairs.size} duplicados"
        override val text = pairs.joinToString("\n") { (item, label) -> "• ${item.name} (ya tenías «$label»)" }
    }

    data class Update(val info: UpdateInfo) : Alert() {
        override val id = 1004
        override val title = "Nueva versión de Controltorbox"
        override val text = "La versión ${info.versionName} está disponible. Toca para actualizar."
    }

    data class Expiring(val items: List<Pair<TorboxItem, Instant>>, val now: Instant) : Alert() {
        override val id = 1005
        override val title = if (items.size == 1) "Caduca pronto en TorBox" else "${items.size} elementos caducan pronto"
        override val text = items.joinToString("\n") { (item, at) -> "• ${Titles.clean(item.name)} (${Expiry.label(at, now)})" }
    }

    data class Storage(val usedBytes: Long, val limitGb: Int) : Alert() {
        override val id = 1003
        override val title = "Almacenamiento de TorBox por encima de $limitGb GB"
        override val text = "Ocupas ${Format.size(usedBytes)}. Revisa lo que puedes borrar."
    }
}

object Monitor {
    private const val GB = 1024L * 1024 * 1024

    /**
     * Compara el estado anterior con los elementos actuales y decide qué avisar.
     * En la primera comprobación (sin estado previo) solo memoriza, no avisa.
     */
    fun check(
        previous: MonitorState?,
        items: List<TorboxItem>,
        settings: AlertSettings,
        now: Instant = Instant.now(),
        statusOf: (TorboxItem) -> AudioStatus = { Language.guessFromName(it.name, it.mainVideo?.displayName) },
    ): Pair<List<Alert>, MonitorState> {
        val keys = items.map { it.key }.toSet()
        val used = items.sumOf { it.totalSize }
        val overLimit = settings.storageLimitGb > 0 && used > settings.storageLimitGb * GB
        if (previous == null) return emptyList<Alert>() to MonitorState(keys, overLimit)

        val alerts = mutableListOf<Alert>()
        val newItems = items.filter { it.key !in previous.knownKeys }

        if (settings.notifyNoSpanish) {
            val statuses = newItems.associate { it.key to statusOf(it) }
            val noEs = newItems.filter { it.mainVideo != null && statuses[it.key] != AudioStatus.ES }
            if (noEs.isNotEmpty()) alerts += Alert.NoSpanish(noEs, statuses)
        }
        if (settings.notifyDuplicates && newItems.isNotEmpty()) {
            val groups = Duplicates.find(items)
            val oldByGroup = items.filter { it.key in previous.knownKeys }
                .mapNotNull { old -> groups[old.key]?.let { it.id to it.label } }.toMap()
            val dups = newItems.mapNotNull { n -> groups[n.key]?.let { g -> oldByGroup[g.id]?.let { n to it } } }
            if (dups.isNotEmpty()) alerts += Alert.Duplicate(dups)
        }
        // Solo se avisa al cruzar el límite (no en cada comprobación mientras siga por encima).
        if (settings.notifyStorage && overLimit && !previous.storageAlerted) {
            alerts += Alert.Storage(used, settings.storageLimitGb)
        }
        // Caducidad: una sola vez por elemento, cuando le quedan pocos días.
        var expiringAlerted = previous.expiringAlerted intersect keys
        if (settings.notifyExpiring) {
            val soon = items.mapNotNull { it.expiresAtInstant?.let { at -> it to at } }
                .filter { (item, at) -> item.key !in expiringAlerted && Expiry.isNotifiable(at, now) }
                .sortedBy { it.second }
            if (soon.isNotEmpty()) {
                alerts += Alert.Expiring(soon, now)
                expiringAlerted = expiringAlerted + soon.map { it.first.key }
            }
        }
        return alerts to MonitorState(keys, overLimit, expiringAlerted)
    }
}

/** Caducidad de los elementos de TorBox. */
object Expiry {
    /** Se muestra «Caduca pronto» con 7 días o menos; se avisa con 3 días o menos. */
    const val SOON_DAYS = 7L
    const val NOTIFY_DAYS = 3L

    private fun hoursLeft(at: Instant, now: Instant) = java.time.Duration.between(now, at).toHours()

    fun isSoon(at: Instant, now: Instant = Instant.now()) = hoursLeft(at, now) < SOON_DAYS * 24
    fun isNotifiable(at: Instant, now: Instant = Instant.now()) = hoursLeft(at, now) < NOTIFY_DAYS * 24

    /** «caduca hoy», «caduca mañana», «caduca en 5 días», «caducado». */
    fun label(at: Instant, now: Instant = Instant.now()): String {
        val h = hoursLeft(at, now)
        return when {
            h < 0 -> "caducado"
            h < 24 -> "caduca hoy"
            h < 48 -> "caduca mañana"
            else -> "caduca en ${h / 24} días"
        }
    }
}

object Format {
    fun size(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val units = listOf("B", "KB", "MB", "GB", "TB")
        var n = bytes.toDouble(); var i = 0
        while (n >= 1024 && i < units.size - 1) { n /= 1024; i++ }
        return if (n >= 100 || i == 0) "%.0f %s".format(n, units[i]) else "%.1f %s".format(n, units[i])
    }

    fun time(seconds: Long): String {
        val s = seconds.coerceAtLeast(0)
        val h = s / 3600; val m = (s % 3600) / 60; val sec = s % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
    }
}
