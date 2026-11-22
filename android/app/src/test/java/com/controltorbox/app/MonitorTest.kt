package com.controltorbox.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MonitorTest {
    private val GB = 1024L * 1024 * 1024
    private fun item(id: Long, name: String, size: Long = GB, kind: Kind = Kind.TORRENT) =
        TorboxItem(kind, id, name, size, null, "", true, listOf(TorboxFile(id * 10, "$name.mkv", "", size)))

    @Test fun firstCheckOnlyRemembers() {
        val (alerts, state) = Monitor.check(null, listOf(item(1, "Movie.2020.VOSE")), AlertSettings(storageLimitGb = 0))
        assertTrue(alerts.isEmpty())
        assertEquals(setOf("torrent:1"), state.knownKeys)
    }

    @Test fun newItemWithoutSpanishAndDuplicate() {
        val old = listOf(item(1, "Oppenheimer.2023.1080p.Castellano"))
        val (_, s1) = Monitor.check(null, old, AlertSettings(storageLimitGb = 0))
        val now = old + item(2, "Oppenheimer (2023) [4K]", kind = Kind.USENET) + item(3, "Dune.Part.Two.2024.Castellano")
        val (alerts, s2) = Monitor.check(s1, now, AlertSettings(storageLimitGb = 0))
        val noEs = alerts.filterIsInstance<Alert.NoSpanish>().single()
        assertEquals(listOf("usenet:2"), noEs.items.map { it.key })
        val dup = alerts.filterIsInstance<Alert.Duplicate>().single()
        assertEquals("usenet:2", dup.pairs.single().first.key)
        assertEquals("Oppenheimer (2023)", dup.pairs.single().second)
        assertEquals(3, s2.knownKeys.size)
        // Sin cambios: no se repiten los avisos
        assertTrue(Monitor.check(s2, now, AlertSettings(storageLimitGb = 0)).first.isEmpty())
    }

    @Test fun disabledAlertsAreNotSent() {
        val (_, s1) = Monitor.check(null, listOf(item(1, "Show.S01E01")), AlertSettings())
        val now = listOf(item(1, "Show.S01E01"), item(2, "Show.S01E01.720p"))
        val off = AlertSettings(notifyNoSpanish = false, notifyDuplicates = false, notifyStorage = false)
        assertTrue(Monitor.check(s1, now, off).first.isEmpty())
    }

    @Test fun storageAlertOnlyWhenCrossingLimit() {
        val settings = AlertSettings(notifyNoSpanish = false, notifyDuplicates = false, storageLimitGb = 10)
        val (_, s1) = Monitor.check(null, listOf(item(1, "A.Castellano", 6 * GB)), settings)
        val over = listOf(item(1, "A.Castellano", 6 * GB), item(2, "B.Castellano", 6 * GB))
        val (a2, s2) = Monitor.check(s1, over, settings)
        assertTrue(a2.single() is Alert.Storage)
        assertTrue(Monitor.check(s2, over, settings).first.isEmpty())           // sigue por encima: no repite
        val (_, s3) = Monitor.check(s2, over.take(1), settings)                  // baja del límite
        assertTrue(Monitor.check(s3, over, settings).first.single() is Alert.Storage) // vuelve a cruzar: avisa
    }

    @Test fun usesAnalyzedStatusWhenProvided() {
        val (_, s1) = Monitor.check(null, emptyList(), AlertSettings(storageLimitGb = 0))
        val now = listOf(item(1, "Movie.2020.1080p"))
        val alerts = Monitor.check(s1, now, AlertSettings(storageLimitGb = 0)) { AudioStatus.ES }.first
        assertTrue(alerts.isEmpty())
    }
}
