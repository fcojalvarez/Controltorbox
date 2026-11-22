package com.controltorbox.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LanguageTest {
    @Test fun guessFromName() {
        val cases = mapOf(
            "Dune.Part.Two.2024.1080p.WEB-DL.Castellano.AC3.mkv" to AudioStatus.ES,
            "Oppenheimer (2023) [BluRay 1080p][Spanish+English]" to AudioStatus.ES,
            "The.Bear.S03E01.1080p.WEB.h264-ETHEL" to AudioStatus.UNKNOWN,
            "Pelicula.2020.VOSE.720p.mkv" to AudioStatus.NO_ES,
            "Movie.2021.1080p.ENG.Subs.Spanish.mkv" to AudioStatus.UNKNOWN,
            "Serie.S01.MULTi.1080p.Latino.mkv" to AudioStatus.ES,
            "Film.2019.DUAL.SPA-ENG.1080p" to AudioStatus.ES,
        )
        for ((name, expected) in cases) assertEquals(name, expected, Language.guessFromName(name))
    }

    @Test fun classify() {
        assertEquals(AudioStatus.ES, Language.classify(listOf("eng", "spa")))
        assertEquals(AudioStatus.NO_ES, Language.classify(listOf("eng")))
        assertEquals(AudioStatus.UNKNOWN, Language.classify(listOf("und")))
        assertEquals(AudioStatus.UNKNOWN, Language.classify(emptyList()))
        assertEquals(AudioStatus.NO_ES, Language.classify(listOf("jpn", "eng")))
        assertEquals(AudioStatus.ES, Language.classify(listOf("es-419")))
        assertEquals(AudioStatus.ES, Language.classify(listOf("es")))  // Android usa códigos de 2 letras
    }

    @Test fun trackLanguageFromLabel() {
        assertEquals("spa", Language.trackLanguage(null, "Castellano AC3 5.1"))
        assertEquals("spa", Language.trackLanguage("und", "Latino"))
        assertEquals("en", Language.trackLanguage("en", "Castellano"))
        assertEquals("und", Language.trackLanguage("", "Commentary"))
    }

    @Test fun pickMainVideo() {
        val files = listOf(
            TorboxFile(1, "a/sample.mkv", "", 10),
            TorboxFile(2, "a/movie.mkv", "", 1000),
            TorboxFile(3, "a/info.nfo", "", 5000),
        )
        assertEquals(2L, Language.pickMainVideo(files)!!.id)
        assertNull(Language.pickMainVideo(listOf(TorboxFile(1, "x.txt", "", 1))))
    }
}
