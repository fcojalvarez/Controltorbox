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

data class TorboxFile(val id: Long, val name: String, val shortName: String, val size: Long) {
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
) {
    val key: String get() = "${kind.key}:$id"
    val mainVideo: TorboxFile? get() = Language.pickMainVideo(files)
    val totalSize: Long get() = if (size > 0) size else files.sumOf { it.size }
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
data class Analysis(val status: AudioStatus, val languages: List<String>, val tracks: List<AudioTrack>)
