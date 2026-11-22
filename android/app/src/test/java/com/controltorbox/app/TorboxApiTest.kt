package com.controltorbox.app

import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class TorboxApiTest {
    private lateinit var server: MockWebServer
    private val requests = mutableListOf<RecordedRequest>()

    @Before fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                if (request.getHeader("Authorization") != "Bearer good") {
                    return MockResponse().setResponseCode(403).setBody("""{"success":false,"detail":"BAD_TOKEN"}""")
                }
                val path = request.requestUrl!!.encodedPath.removePrefix("/v1/api/")
                return when (path) {
                    "torrents/mylist" -> ok("""[{"id":1,"name":"Movie.2023.Castellano","size":100,"hash":"abc",
                        "created_at":"2026-11-01T10:00:00Z","download_state":"completed",
                        "files":[{"id":10,"name":"Movie/m.mkv","short_name":"m.mkv","size":100,"mimetype":"video/x-matroska"}]}]""")
                    "usenet/mylist" -> ok("[]")
                    "webdl/mylist" -> MockResponse().setResponseCode(500).setBody("""{"success":false,"detail":"caído"}""")
                    "torrents/controltorrent" -> ok("null")
                    "torrents/requestdl" -> ok("\"https://cdn.example/file.mkv\"")
                    "user/me" -> ok("""{"id":1,"plan":2,"email":"x@y","total_downloaded":5,"is_subscribed":true}""")
                    else -> MockResponse().setResponseCode(404).setBody("""{"success":false,"detail":"nope"}""")
                }
            }
        }
        server.start()
    }

    private fun ok(data: String) = MockResponse().setBody("""{"success":true,"data":$data}""")

    @After fun tearDown() = server.shutdown()

    private fun api(token: String = "good") = TorboxApi(token, server.url("/v1/api/").toString())

    @Test fun listAllParsesAndToleratesPartialErrors() = runTest {
        val (items, errors) = api().listAll()
        val it = items.single()
        assertEquals(Kind.TORRENT, it.kind)
        assertEquals("Movie.2023.Castellano", it.name)
        assertEquals("2026-11-01T10:00:00Z", it.createdAt)
        assertEquals("m.mkv", it.mainVideo!!.displayName)
        assertEquals("video/x-matroska", it.files.single().mimetype)
        assertTrue(it.downloadFinished)
        assertEquals(1, errors.size)
        assertTrue(errors.single().contains("caído"))
    }

    @Test fun badTokenIsReported() = runTest {
        try {
            api("bad").listAll(); fail()
        } catch (e: TorboxException) {
            assertTrue(e.message!!.contains("BAD_TOKEN"))
        }
    }

    @Test fun deleteSendsExpectedBody() = runTest {
        api().delete(Kind.TORRENT, 1)
        val req = requests.last()
        assertEquals("POST", req.method)
        assertEquals("/v1/api/torrents/controltorrent", req.requestUrl!!.encodedPath)
        val body = JSONObject(req.body.readUtf8())
        assertEquals(1, body.getInt("torrent_id"))
        assertEquals("delete", body.getString("operation"))
        assertEquals(false, body.getBoolean("all"))
        assertEquals("application/json; charset=utf-8", req.getHeader("Content-Type"))
    }

    @Test fun downloadLink() = runTest {
        assertEquals("https://cdn.example/file.mkv", api().downloadLink(Kind.TORRENT, 1, 10))
        val u = requests.last().requestUrl!!
        assertEquals("1", u.queryParameter("torrent_id"))
        assertEquals("10", u.queryParameter("file_id"))
        assertEquals("false", u.queryParameter("redirect"))
    }

    @Test fun accountReadsPlan() = runTest {
        val a = api().account()
        assertEquals(2, a.plan)
        assertEquals("Pro", a.planName)
        assertEquals(null, a.storageLimitBytes)   // la API documentada no lo trae
        assertEquals("false", requests.last().requestUrl!!.queryParameter("settings"))
    }

    @Test fun accountDetectsStorageLimitIfPresent() {
        val gb = 1024L * 1024 * 1024
        assertEquals(1000 * gb, TorboxApi.parseAccount(JSONObject("""{"plan":3,"storage_limit":1000}""")).storageLimitBytes)
        assertEquals(2000 * gb, TorboxApi.parseAccount(JSONObject("""{"plan":2,"max_storage_bytes":${2000 * gb}}""")).storageLimitBytes)
        assertEquals(null, TorboxApi.parseAccount(JSONObject("""{"plan":1,"storage_used":${5 * gb}}""")).storageLimitBytes)
        assertEquals(null, TorboxApi.parseAccount(JSONObject("""{"total_downloaded":123}""")).storageLimitBytes)
    }

    @Test(expected = TorboxException::class) fun emptyTokenFails() { TorboxApi("") }
}
