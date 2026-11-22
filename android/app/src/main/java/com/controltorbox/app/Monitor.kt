package com.controltorbox.app

/** Ajustes de los avisos. */
data class AlertSettings(
    val notifyNoSpanish: Boolean = true,
    val notifyDuplicates: Boolean = true,
    val notifyStorage: Boolean = true,
    val storageLimitGb: Int = 500,
    val notifyUpdates: Boolean = true,
)

/** Lo que recordamos entre comprobaciones. */
data class MonitorState(val knownKeys: Set<String>, val storageAlerted: Boolean)

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
        return alerts to MonitorState(keys, overLimit)
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
