package com.controltorbox.app

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Lee y reproduce las pistas de audio con el reproductor de Android (Media3/ExoPlayer).
 * Solo descarga lo necesario del archivo (peticiones por rangos), no la película entera.
 */
@OptIn(UnstableApi::class)
object AudioProbe {

    private fun newPlayer(context: Context): ExoPlayer = ExoPlayer.Builder(context).build().apply {
        // Solo nos interesa el audio: no decodificamos vídeo ni subtítulos.
        trackSelectionParameters = trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true)
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            .build()
    }

    fun audioTracks(tracks: Tracks): List<AudioTrack> {
        val out = mutableListOf<AudioTrack>()
        for (group in tracks.groups) {
            if (group.type != C.TRACK_TYPE_AUDIO) continue
            for (i in 0 until group.length) {
                val f = group.getTrackFormat(i)
                out += AudioTrack(
                    index = out.size,
                    language = Language.trackLanguage(f.language, f.label),
                    label = f.label.orEmpty(),
                    codec = codecName(f.sampleMimeType ?: f.codecs.orEmpty()),
                    channels = if (f.channelCount == androidx.media3.common.Format.NO_VALUE) 0 else f.channelCount,
                    supported = group.isTrackSupported(i),
                )
            }
        }
        return out
    }

    private fun codecName(mime: String): String = when {
        mime.contains("eac3", true) || mime.contains("ec-3", true) -> "E-AC3"
        mime.contains("ac3", true) || mime.contains("ac-3", true) -> "AC3"
        mime.contains("dts", true) -> "DTS"
        mime.contains("truehd", true) -> "TrueHD"
        mime.contains("mp4a", true) || mime.contains("aac", true) -> "AAC"
        mime.contains("opus", true) -> "Opus"
        mime.contains("vorbis", true) -> "Vorbis"
        mime.contains("mpeg", true) -> "MP3"
        mime.contains("flac", true) -> "FLAC"
        else -> mime.substringAfter('/').uppercase()
    }

    /** Abre la URL, lee las pistas de audio y libera el reproductor. */
    suspend fun probe(context: Context, url: String, timeoutMs: Long = 45_000): List<AudioTrack> =
        withContext(Dispatchers.Main) {
            val player = newPlayer(context)
            try {
                withTimeout(timeoutMs) {
                    suspendCancellableCoroutine { cont ->
                        player.addListener(object : Player.Listener {
                            override fun onTracksChanged(tracks: Tracks) {
                                if (!tracks.isEmpty && cont.isActive) cont.resume(audioTracks(tracks))
                            }

                            override fun onPlayerError(error: PlaybackException) {
                                if (cont.isActive) cont.resumeWithException(
                                    TorboxException("No se pudo leer el archivo: ${error.errorCodeName}"),
                                )
                            }
                        })
                        player.setMediaItem(MediaItem.fromUri(url))
                        player.prepare()
                    }
                }
            } finally {
                player.release()
            }
        }

    /** Reproductor para escuchar una pista concreta desde una posición. */
    class Preview(context: Context) {
        val player: ExoPlayer = newPlayer(context)
        private var currentUrl: String? = null

        fun play(url: String, trackIndex: Int, positionMs: Long) {
            if (url != currentUrl) {
                player.setMediaItem(MediaItem.fromUri(url))
                player.prepare()
                currentUrl = url
            }
            selectTrack(trackIndex)
            player.seekTo(positionMs)
            player.playWhenReady = true
        }

        /** Selecciona la pista n-ésima de audio (en el mismo orden que [audioTracks]). */
        fun selectTrack(trackIndex: Int) {
            pendingListener?.let { player.removeListener(it) }
            pendingListener = null
            if (!applyTrack(trackIndex)) {
                // Las pistas aún no se conocen: se aplica en cuanto lleguen.
                val listener = object : Player.Listener {
                    override fun onTracksChanged(tracks: Tracks) {
                        if (!tracks.isEmpty && applyTrack(trackIndex)) {
                            player.removeListener(this)
                            pendingListener = null
                        }
                    }
                }
                pendingListener = listener
                player.addListener(listener)
            }
        }

        private var pendingListener: Player.Listener? = null

        private fun applyTrack(trackIndex: Int): Boolean {
            var n = 0
            for (group in player.currentTracks.groups) {
                if (group.type != C.TRACK_TYPE_AUDIO) continue
                for (i in 0 until group.length) {
                    if (n == trackIndex) {
                        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                            .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, i))
                            .build()
                        return true
                    }
                    n++
                }
            }
            return false
        }

        fun stop() {
            player.playWhenReady = false
        }

        fun release() = player.release()
    }
}
