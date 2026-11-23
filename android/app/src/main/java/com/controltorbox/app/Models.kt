package com.controltorbox.app

/** Tipos de descarga de TorBox que ocupan almacenamiento. */
enum class Kind(
    val key: String,
    val label: String,
    val listPath: String,
    val controlPath: String,
    val controlIdField: String,
    val requestDlPath: String,
    val requestDlIdParam: String,
) {
    TORRENT("torrent", "Torrent", "torrents/mylist", "torrents/controltorrent", "torrent_id", "torrents/requestdl", "torrent_id"),
    USENET("usenet", "Usenet", "usenet/mylist", "usenet/controlusenetdownload", "usenet_id", "usenet/requestdl", "usenet_id"),
    WEBDL("webdl", "Web", "webdl/mylist", "webdl/controlwebdownload", "webdl_id", "webdl/requestdl", "web_id");

    companion object {
        fun fromKey(key: String): Kind = entries.first { it.key == key }
    }
}

data class TorboxFile(val id: Long, val name: String, val shortName: String, val size: Long, val mimetype: String = "") {
    /** Vídeo por extensión o por el tipo que indica TorBox (p. ej. «video/x-matroska»). */
    val isVideo: Boolean get() = Language.isVideo(name) || Language.isVideo(shortName) || mimetype.startsWith("video/", ignoreCase = true)

    val displayName: String get() = shortName.ifBlank { name.substringAfterLast('/') }
}

data class TorboxItem(
    val kind: Kind,
    val id: Long,
    val name: String,
    val size: Long,
    val createdAt: String?,
    val hash: String,
    val downloadFinished: Boolean,
    val files: List<TorboxFile>,
    /** Estado de descarga según TorBox («downloading», «stalled (no seeds)», «paused»…). */
    val state: String = "",
    /** Progreso de 0 a 1. */
    val progress: Float = if (downloadFinished) 1f else 0f,
    val downloadSpeed: Long = 0,
    /** Segundos que faltan (null si TorBox no lo sabe). */
    val eta: Long? = null,
    val seeds: Int? = null,
    val peers: Int? = null,
    val availability: Double? = null,
    /** Cuándo lo quitará TorBox (null si no caduca o no lo indica). */
    val expiresAt: String? = null,
) {
    val key: String get() = "${kind.key}:$id"

    val expiresAtInstant: java.time.Instant? get() = UiState.parseDate(expiresAt)

    /** Aún descargándose (o parado sin terminar). */
    val inProgress: Boolean get() = !downloadFinished

    val paused: Boolean get() = state.contains("pause", ignoreCase = true)

    /**
     * Atascada: sin terminar y TorBox dice «stalled»/error, o sin fuentes ni velocidad.
     * Las pausadas no cuentan como atascadas.
     */
    val stalled: Boolean
        get() {
            if (downloadFinished || paused) return false
            val s = state.lowercase()
            if ("stalled" in s || "error" in s || "missing" in s || "failed" in s) return true
            // Fuentes y disponibilidad solo tienen sentido en torrents (Usenet no tiene «seeds»).
            if (kind != Kind.TORRENT) return false
            val noSources = (seeds ?: -1) == 0 || (availability != null && availability < 1.0)
            return noSources && downloadSpeed == 0L
        }
    val mainVideo: TorboxFile? get() = Language.pickMainVideo(files)

    /** Archivos de vídeo sin «samples», ordenados por nombre (los episodios de una temporada). */
    val videoFiles: List<TorboxFile>
        get() {
            val videos = files.filter { it.isVideo }
            val biggest = videos.maxOfOrNull { it.size } ?: 0
            return videos.filterNot {
                Regex("\\bsample\\b", RegexOption.IGNORE_CASE).containsMatchIn(it.name.replace('.', ' ')) && it.size < biggest / 4
            }.sortedBy { it.displayName.lowercase() }
        }
    val totalSize: Long get() = if (size > 0) size else files.sumOf { it.size }
}

/** Algo en la cola de TorBox, esperando a empezar. */
data class QueuedItem(val kind: Kind, val id: Long, val name: String, val createdAt: String?, val hash: String) {
    val key: String get() = "queued:${kind.key}:$id"
}

/** Datos de la cuenta de TorBox. */
data class AccountInfo(val plan: Int?, val storageLimitBytes: Long?) {
    val planName: String? get() = when (plan) {
        0 -> "Free"; 1 -> "Essential"; 2 -> "Pro"; 3 -> "Standard"; else -> null
    }
}

enum class AudioStatus(val label: String) { ES("Español"), NO_ES("Sin español"), UNKNOWN("Desconocido") }

enum class AudioSource(val label: String) { NAME("por nombre"), ANALYZED("analizado"), MANUAL("marcado a mano") }

/** Pista de audio detectada al analizar el archivo. */
data class AudioTrack(
    val index: Int,
    val language: String,
    val label: String,
    val codec: String,
    val channels: Int,
    val supported: Boolean,
)

/** Resultado guardado del análisis de un elemento. */
data class Analysis(
    val status: AudioStatus,
    val languages: List<String>,
    val tracks: List<AudioTrack>,
    /** En series con varios episodios: el análisis de cada uno. */
    val episodes: List<EpisodeAnalysis> = emptyList(),
) {
    /** «8 de 10 episodios con español», «2 de 10 sin español»… (null si no es una serie). */
    val episodeSummary: String?
        get() {
            if (episodes.isEmpty()) return null
            val n = episodes.size
            val noEs = episodes.count { it.status == AudioStatus.NO_ES }
            val unk = episodes.count { it.status == AudioStatus.UNKNOWN }
            val es = n - noEs - unk
            return when {
                es == n -> "Los $n episodios con español"
                noEs == n -> "Ningún episodio con español"
                else -> listOfNotNull(
                    if (noEs > 0) "$noEs de $n episodios sin español" else null,
                    if (unk > 0) "$unk sin poder leer" else null,
                ).joinToString(" · ")
            }
        }
}

/** Análisis de un episodio (un archivo de vídeo) dentro de una serie. */
data class EpisodeAnalysis(
    val fileId: Long,
    val name: String,
    val status: AudioStatus,
    val languages: List<String>,
    val tracks: List<AudioTrack>,
    val error: String? = null,
) {
    /** «T3 E2» si el nombre lo indica; si no, el nombre del archivo. */
    val label: String get() = episodeLabel(name)
}

fun episodeLabel(fileName: String): String {
    val base = fileName.substringAfterLast('/')
    val m = Regex("[Ss](\\d{1,2})[ ._-]?[Ee](\\d{1,3})").find(base) ?: Regex("\\b(\\d{1,2})x(\\d{2,3})\\b").find(base)
    return if (m != null) "T${m.groupValues[1].toInt()} E${m.groupValues[2].toInt()}" else base.substringBeforeLast('.')
}
