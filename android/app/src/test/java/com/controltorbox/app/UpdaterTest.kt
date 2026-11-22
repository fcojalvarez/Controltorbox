package com.controltorbox.app

import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class UpdaterTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var server: MockWebServer
    private var versionJson = """{"versionCode": 12, "versionName": "1.0.12", "apk": "Controltorbox.apk"}"""
    private val apkBytes = ByteArray(200_000) { (it % 251).toByte() }

    @Before fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                // GitHub redirige las descargas de «releases»: comprobamos que se sigue la redirección.
                "/releases/download/android-latest/version.json" ->
                    MockResponse().setResponseCode(302).setHeader("Location", "/cdn/version.json")
                "/cdn/version.json" -> MockResponse().setBody(versionJson)
                "/releases/download/android-latest/Controltorbox.apk" -> MockResponse().setBody(Buffer().write(apkBytes))
                else -> MockResponse().setResponseCode(404)
            }
        }
        server.start()
    }

    @After fun tearDown() = server.shutdown()

    private fun updater() = Updater(server.url("/releases/download/android-latest/version.json").toString())

    @Test fun latestFollowsRedirectAndBuildsApkUrl() = runTest {
        val info = updater().latest()
        assertEquals(12, info.versionCode)
        assertEquals("1.0.12", info.versionName)
        assertEquals(server.url("/releases/download/android-latest/Controltorbox.apk").toString(), info.apkUrl)
    }

    @Test fun checkOnlyReportsNewerVersions() = runTest {
        assertEquals(12, updater().check(currentCode = 11)!!.versionCode)
        assertNull(updater().check(currentCode = 12))
        assertNull(updater().check(currentCode = 13))
    }

    @Test(expected = TorboxException::class) fun invalidJsonFails() = runTest {
        versionJson = "<html>no es json</html>"
        updater().latest()
    }

    @Test fun parseAbsoluteApkUrl() {
        val i = Updater.parse("""{"versionCode":3,"apk":"https://x.example/a.apk"}""", "https://h/v.json")
        assertEquals("https://x.example/a.apk", i.apkUrl)
        assertEquals("3", i.versionName)
    }

    @Test fun downloadWritesApkAndReportsProgress() = runTest {
        val info = updater().latest()
        val ctx = object : android.content.ContextWrapper(null) {
            override fun getCacheDir() = tmp.root
        }
        var last = 0f
        val file = updater().download(ctx, info) { last = it }
        assertArrayEquals(apkBytes, file.readBytes())
        assertEquals(1f, last, 0.001f)
        assertEquals("Controltorbox-12.apk", file.name)
    }
}
