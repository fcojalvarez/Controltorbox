package com.controltorbox.app

import org.junit.Assert.assertEquals
import org.junit.Test

class TitlesTest {
    @Test fun clean() {
        assertEquals("Oppenheimer (2023)", Titles.clean("Oppenheimer.2023.1080p.BluRay.x264"))
        assertEquals("Oppenheimer (2023)", Titles.clean("Oppenheimer (2023) [4K HDR][Dual]"))
        assertEquals("Dune Part Two (2024)", Titles.clean("Dune.Part.Two.2024.2160p.WEB-DL.Castellano.AC3"))
        assertEquals("The Bear · T3", Titles.clean("The.Bear.S03.1080p.WEB.h264-ETHEL"))
        assertEquals("The Bear · T3 E2", Titles.clean("The.Bear.S03E02.720p.HDTV"))
        assertEquals("Shogun · T1 E1", Titles.clean("Shogun.2024.S01E01.VOSE.1080p"))
        assertEquals("Breaking Bad · T1–T5", Titles.clean("Breaking.Bad.S01-S05.COMPLETE.1080p"))
        assertEquals("La Casa de Papel · T2", Titles.clean("La.Casa.de.Papel.S02.1080p"))
        assertEquals("x", Titles.clean("x"))
    }

    @Test fun quality() {
        assertEquals("4K HDR", Titles.quality("Oppenheimer (2023) [4K HDR][Dual]"))
        assertEquals("1080p BluRay", Titles.quality("Oppenheimer.2023.1080p.BluRay.x264"))
        assertEquals("4K WEB", Titles.quality("Dune.Part.Two.2024.2160p.WEB-DL.Castellano"))
        assertEquals("720p HDTV", Titles.quality("The.Bear.S03E02.720p.HDTV"))
        assertEquals("1080p VOSE", Titles.quality("Shogun.2024.S01E01.VOSE.1080p"))
        assertEquals("", Titles.quality("Pelicula"))
    }
}
