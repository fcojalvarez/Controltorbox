package com.controltorbox.app

import android.content.Context
import android.content.ContextWrapper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EpisodesTest {
    private val ES = AudioStatus.ES
    private val NO = AudioStatus.NO_ES
    private val UNK = AudioStatus.UNKNOWN

    @Test fun combine() {
        assertEquals(ES, Language.combine(listOf(ES, ES, ES)))
        assertEquals(NO, Language.combine(listOf(ES, NO, ES)))      // falta en uno → sin español
        assertEquals(NO, Language.combine(listOf(UNK, NO)))
        assertEquals(UNK, Language.combine(listOf(ES, UNK)))        // uno sin poder leer
        assertEquals(UNK, Language.combine(emptyList()))
    }

    private fun ep(id: Long, status: AudioStatus, name: String = "Show.S01E0$id.mkv") =
        EpisodeAnalysis(id, name, status, emptyList(), emptyList())

    @Test fun summary() {
        assertNull(Analysis(ES, listOf("spa"), emptyList()).episodeSummary)
        assertEquals("Los 3 episodios con español", Analysis(ES, emptyList(), emptyList(), listOf(ep(1, ES), ep(2, ES), ep(3, ES))).episodeSummary)
        assertEquals("1 de 3 episodios sin español", Analysis(NO, emptyList(), emptyList(), listOf(ep(1, ES), ep(2, NO), ep(3, ES))).episodeSummary)
        assertEquals("1 de 3 episodios sin español · 1 sin poder leer", Analysis(NO, emptyList(), emptyList(), listOf(ep(1, UNK), ep(2, NO), ep(3, ES))).episodeSummary)
        assertEquals("Ningún episodio con español", Analysis(NO, emptyList(), emptyList(), listOf(ep(1, NO), ep(2, NO))).episodeSummary)
    }

    @Test fun labels() {
        assertEquals("T3 E2", episodeLabel("The.Bear.S03E02.1080p.mkv"))
        assertEquals("T1 E10", episodeLabel("Show/Show S01 E10 720p.mkv"))
        assertEquals("T3 E5", episodeLabel("Los Simpson 3x05.avi"))
        assertEquals("Capitulo final", episodeLabel("Capitulo final.mkv"))
    }

    @Test fun videoFilesSkipSamplesAndSortByName() {
        val GB = 1024L * 1024 * 1024
        val item = TorboxItem(Kind.TORRENT, 1, "Show.S01", 0, null, "", true, listOf(
            TorboxFile(3, "Show/Show.S01E02.mkv", "Show.S01E02.mkv", GB),
            TorboxFile(1, "Show/Show.S01E01.mkv", "Show.S01E01.mkv", GB),
            TorboxFile(9, "Show/Sample/show.sample.mkv", "show.sample.mkv", 50L * 1024 * 1024),
            TorboxFile(5, "Show/info.nfo", "info.nfo", 100),
        ))
        assertEquals(listOf(1L, 3L), item.videoFiles.map { it.id })
    }

    @Test fun storeRoundTripWithEpisodes() {
        val prefs = MemoryPrefs()
        val ctx = object : ContextWrapper(null) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String?, mode: Int) = prefs
        }
        val store = Store(ctx)
        val track = AudioTrack(0, "spa", "Castellano", "AC3", 6, true)
        val a = Analysis(NO, listOf("spa", "en"), listOf(track), listOf(
            EpisodeAnalysis(1, "Show.S01E01.mkv", ES, listOf("spa"), listOf(track)),
            EpisodeAnalysis(2, "Show.S01E02.mkv", NO, listOf("en"), emptyList()),
            EpisodeAnalysis(3, "Show.S01E03.mkv", UNK, emptyList(), emptyList(), "timeout"),
        ))
        store.saveAnalysis("torrent:1", a)
        assertEquals(a, Store(ctx).analyses()["torrent:1"])
        // Análisis antiguos (sin episodios) se siguen leyendo.
        val single = Analysis(ES, listOf("spa"), listOf(track))
        store.saveAnalysis("torrent:2", single)
        assertEquals(single, store.analyses()["torrent:2"])
    }
}
