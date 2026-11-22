package com.controltorbox.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DuplicatesTest {
    private data class Case(val name: String, val title: String, val year: Int?, val seasons: Set<Int>, val episode: Int?)

    @Test fun parseName() {
        val cases = listOf(
            Case("Dune.Part.Two.2024.2160p.WEB-DL.Castellano.AC3", "dune part two", 2024, emptySet(), null),
            Case("Dune Part Two (2024) [1080p][Castellano AC3 5.1]", "dune part two", 2024, emptySet(), null),
            Case("Oppenheimer [BluRay 1080p][Castellano AC3]", "oppenheimer", null, emptySet(), null),
            Case("[ www.Torrent.com ] - Oppenheimer.2023.1080p.BluRay.x264.mkv", "oppenheimer", 2023, emptySet(), null),
            Case("The.Bear.S03E01.1080p.WEB.h264-ETHEL", "the bear", null, setOf(3), 1),
            Case("The.Bear.S03.1080p.WEB.h264-ETHEL", "the bear", null, setOf(3), null),
            Case("The Bear - Temporada 3 [HDTV 720p][Cap.301]", "the bear", null, setOf(3), null),
            Case("Breaking.Bad.S01-S05.COMPLETE.1080p", "breaking bad", null, setOf(1, 2, 3, 4, 5), null),
            Case("Shogun.2024.S01E01.VOSE.1080p", "shogun", 2024, setOf(1), 1),
            Case("Los Simpson 3x05 [HDTV]", "los simpson", null, setOf(3), 5),
            Case("1917.2019.1080p.BluRay", "1917", 2019, emptySet(), null),
            Case("Pelicula Española.2020.mkv", "pelicula espanola", 2020, emptySet(), null),
        )
        for (c in cases) {
            val p = Duplicates.parseName(c.name)
            assertEquals(c.name, Case(c.name, c.title, c.year, c.seasons, c.episode), Case(c.name, p.title, p.year, p.seasons, p.episode))
        }
    }

    private fun items(vararg names: String, hashes: Map<Int, String> = emptyMap()) =
        names.mapIndexed { i, n -> TorboxItem(Kind.TORRENT, i.toLong(), n, 1, null, hashes[i] ?: "", true, emptyList()) }

    private fun groups(r: Map<String, Duplicates.Group>): List<List<Int>> =
        r.entries.groupBy({ it.value.id }, { it.key.substringAfter(':').toInt() })
            .values.map { it.sorted() }.sortedBy { it.first() }

    @Test fun sameMovieDifferentQuality() {
        val r = Duplicates.find(items(
            "Dune.Part.Two.2024.2160p.WEB-DL.Castellano.AC3",
            "Dune Part Two (2024) [1080p][Castellano AC3 5.1]",
            "Oppenheimer.2023.1080p",
        ))
        assertEquals(listOf(listOf(0, 1)), groups(r))
        assertEquals("Dune Part Two (2024)", r["torrent:0"]!!.label)
    }

    @Test fun remakesAreNotDuplicates() =
        assertTrue(Duplicates.find(items("Dune.1984.1080p.BluRay", "Dune.2021.2160p.WEB")).isEmpty())

    @Test fun movieWithoutYearMatches() =
        assertEquals(listOf(listOf(0, 1)), groups(Duplicates.find(items("Oppenheimer [BluRay 1080p]", "Oppenheimer.2023.2160p"))))

    @Test fun seriesEpisodesAndPacks() {
        val r = Duplicates.find(items(
            "The.Bear.S03E01.1080p.WEB", "The Bear S03E01 720p HDTV", "The.Bear.S03E02.1080p.WEB",
            "The.Bear.S03.1080p.WEB", "The.Bear.S02E01.1080p", "The.Bear.S04E01.1080p",
        ))
        assertEquals(listOf(listOf(0, 1, 2, 3)), groups(r))
    }

    @Test fun differentEpisodesAreNotDuplicates() =
        assertTrue(Duplicates.find(items("Show.S01E01.1080p", "Show.S01E02.1080p")).isEmpty())

    @Test fun completeSeriesCoversSeasons() =
        assertEquals(listOf(listOf(0, 1)), groups(Duplicates.find(items("Breaking.Bad.S01-S05.1080p", "Breaking Bad S04E10 720p"))))

    @Test fun movieAndSeriesWithSameTitle() =
        assertTrue(Duplicates.find(items("Fargo.1996.1080p", "Fargo.S01E01.1080p")).isEmpty())

    @Test fun sameHash() =
        assertEquals(listOf(listOf(0, 1)), groups(Duplicates.find(items("abc", "xyz", hashes = mapOf(0 to "ABC123", 1 to "abc123")))))
}
