package com.controltorbox.app

import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant

class DownloadsTest {
    private fun item(state: String = "downloading", finished: Boolean = false, seeds: Int? = 5, speed: Long = 1000,
                     availability: Double? = null, kind: Kind = Kind.TORRENT, expires: String? = null, id: Long = 1) =
        TorboxItem(kind, id, "Movie.$id.2020.1080p", 100, null, "", finished, emptyList(), state, 0.5f, speed, 60, seeds, 3, availability, expires)

    @Test fun parsesDownloadFieldsInBothCasings() {
        val snake = TorboxApi.parseItem(Kind.TORRENT, JSONObject("""{"id":1,"name":"A","size":10,"download_state":"downloading",
            "download_finished":false,"progress":0.42,"download_speed":2048,"eta":600,"seeds":7,"peers":2,"availability":1.5,
            "expires_at":"2026-12-01T10:00:00Z","files":[]}"""))
        assertEquals("downloading", snake.state)
        assertEquals(0.42f, snake.progress, 0.001f)
        assertEquals(2048L, snake.downloadSpeed)
        assertEquals(600L, snake.eta)
        assertEquals(7, snake.seeds)
        assertEquals(1.5, snake.availability!!, 0.001)
        assertEquals(Instant.parse("2026-12-01T10:00:00Z"), snake.expiresAtInstant)

        val camel = TorboxApi.parseItem(Kind.TORRENT, JSONObject("""{"id":2,"name":"B","size":10,"downloadState":"stalled (no seeds)",
            "downloadFinished":false,"progress":37,"downloadSpeed":0,"seeds":0,"expiresAt":"2026-12-02T10:00:00Z","files":[]}"""))
        assertEquals(0.37f, camel.progress, 0.001f)      // 0–100 → 0–1
        assertTrue(camel.stalled)
        assertEquals(Instant.parse("2026-12-02T10:00:00Z"), camel.expiresAtInstant)

        val done = TorboxApi.parseItem(Kind.USENET, JSONObject("""{"id":3,"name":"C","download_state":"completed","files":[]}"""))
        assertFalse(done.inProgress)
        assertEquals(1f, done.progress, 0.001f)
        assertNull(done.expiresAtInstant)
    }

    @Test fun stalledLogic() {
        assertFalse(item().stalled)
        assertTrue(item(state = "stalled (no seeds)").stalled)
        assertTrue(item(state = "error").stalled)
        assertTrue(item(seeds = 0, speed = 0).stalled)
        assertFalse(item(seeds = 0, speed = 500).stalled)                    // aún baja algo
        assertTrue(item(availability = 0.4, seeds = null, speed = 0).stalled)
        assertFalse(item(state = "paused", seeds = 0, speed = 0).stalled)    // pausada no es atascada
        assertFalse(item(state = "stalled", finished = true).stalled)
        assertFalse(item(kind = Kind.USENET, seeds = 0, speed = 0).stalled)   // Usenet no tiene fuentes
        assertTrue(item(kind = Kind.USENET, state = "failed").stalled)
    }

    @Test fun expiryLabels() {
        val now = Instant.parse("2026-11-23T12:00:00Z")
        assertEquals("caduca hoy", Expiry.label(now.plus(Duration.ofHours(5)), now))
        assertEquals("caduca mañana", Expiry.label(now.plus(Duration.ofHours(30)), now))
        assertEquals("caduca en 5 días", Expiry.label(now.plus(Duration.ofDays(5)), now))
        assertEquals("caducado", Expiry.label(now.minus(Duration.ofHours(1)), now))
        assertTrue(Expiry.isSoon(now.plus(Duration.ofDays(6)), now))
        assertFalse(Expiry.isSoon(now.plus(Duration.ofDays(8)), now))
        assertTrue(Expiry.isNotifiable(now.plus(Duration.ofDays(2)), now))
        assertFalse(Expiry.isNotifiable(now.plus(Duration.ofDays(4)), now))
    }

    @Test fun expiringAlertOnlyOncePerItem() {
        val now = Instant.parse("2026-11-23T12:00:00Z")
        val settings = AlertSettings(notifyNoSpanish = false, notifyDuplicates = false, storageLimitGb = 0)
        val soon = item(finished = true, expires = "2026-11-25T12:00:00Z", id = 1)
        val later = item(finished = true, expires = "2026-12-20T12:00:00Z", id = 2)
        val (_, s1) = Monitor.check(null, listOf(soon, later), settings, now)
        val (a2, s2) = Monitor.check(s1, listOf(soon, later), settings, now)
        val alert = a2.single() as Alert.Expiring
        assertEquals(listOf("torrent:1"), alert.items.map { it.first.key })
        assertTrue(alert.text.contains("caduca en 2 días"))
        assertTrue(Monitor.check(s2, listOf(soon, later), settings, now).first.isEmpty())      // no se repite
        // Más adelante el otro también entra en plazo: se avisa solo de ese.
        val laterNow = Instant.parse("2026-12-18T13:00:00Z")
        val a4 = Monitor.check(s2, listOf(soon, later), settings, laterNow).first.single() as Alert.Expiring
        assertEquals(listOf("torrent:2"), a4.items.map { it.first.key })
        // Desactivado en ajustes: nada.
        assertTrue(Monitor.check(s1, listOf(soon), settings.copy(notifyExpiring = false), now).first.isEmpty())
    }

    @Test fun queuedAndControlRequests() = runTest {
        val requests = mutableListOf<RecordedRequest>()
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                val path = request.requestUrl!!.encodedPath.removePrefix("/v1/api/")
                val data = when (path) {
                    "queued/getqueued" -> """[{"id":77,"name":"Show.S01.1080p","created_at":"2026-11-22T10:00:00Z","hash":"abc"}]"""
                    else -> "null"
                }
                return MockResponse().setBody("""{"success":true,"data":$data}""")
            }
        }
        server.start()
        try {
            val api = TorboxApi("good", server.url("/v1/api/").toString())
            val q = api.queued(Kind.USENET).single()
            assertEquals(77L, q.id)
            assertEquals("usenet", requests.last().requestUrl!!.queryParameter("type"))
            api.controlQueued(q, "start")
            val body = JSONObject(requests.last().body.readUtf8())
            assertEquals(77, body.getInt("queued_id"))
            assertEquals("start", body.getString("operation"))
            assertEquals("usenet", body.getString("type"))
            api.control(Kind.TORRENT, 5, "reannounce")
            val r = requests.last()
            assertEquals("/v1/api/torrents/controltorrent", r.requestUrl!!.encodedPath)
            val b2 = JSONObject(r.body.readUtf8())
            assertEquals(5, b2.getInt("torrent_id"))
            assertEquals("reannounce", b2.getString("operation"))
        } finally {
            server.shutdown()
        }
    }
}
