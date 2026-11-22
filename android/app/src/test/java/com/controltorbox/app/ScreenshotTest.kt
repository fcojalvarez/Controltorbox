package com.controltorbox.app

import android.app.Application
import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import org.junit.Rule
import org.junit.Test

/** Capturas de las pantallas con datos de ejemplo (./gradlew recordPaparazziDebug). */
class ScreenshotTest {
    @get:Rule val paparazzi = Paparazzi(deviceConfig = DeviceConfig.PIXEL_5, showSystemUi = false, maxPercentDifference = 0.1)

    private class TestApp(base: Context) : Application() {
        private val prefs = MemoryPrefs()
        init { attachBaseContext(base) }
        override fun getApplicationContext(): Context = this
        override fun getSharedPreferences(name: String?, mode: Int) = prefs
    }

    private val GB = 1024L * 1024 * 1024
    private fun item(kind: Kind, id: Long, name: String, gb: Double, date: String?, files: List<String> = listOf("$name.mkv")) =
        TorboxItem(kind, id, name, (gb * GB).toLong(), date, "", true,
            files.mapIndexed { i, f -> TorboxFile(id * 10 + i, f, f, (gb * GB / files.size).toLong()) })

    private val sample = listOf(
        item(Kind.TORRENT, 1, "Dune.Part.Two.2024.2160p.WEB-DL.Castellano.AC3", 18.0, "2026-10-02T10:00:00Z"),
        item(Kind.TORRENT, 2, "The.Bear.S03.1080p.WEB.h264-ETHEL", 9.0, "2026-11-05T21:30:00Z",
            listOf("The.Bear.S03E01.mkv", "The.Bear.S03E02.mkv", "The.Bear.S03E03.mkv")),
        item(Kind.TORRENT, 3, "Shogun.2024.S01E01.VOSE.1080p", 3.2, "2025-01-15T08:00:00Z"),
        item(Kind.TORRENT, 4, "Oppenheimer.2023.1080p.BluRay.x264", 14.0, "2026-06-20T08:00:00Z"),
        item(Kind.USENET, 9, "Oppenheimer (2023) [4K HDR][Dual]", 32.0, "2026-11-02T10:00:00Z"),
        item(Kind.USENET, 10, "The.Bear.S03E02.720p.HDTV", 1.2, "2026-11-03T10:00:00Z"),
    )

    private fun vm(update: UpdateInfo? = null): MainViewModel {
        val app = TestApp(paparazzi.context)
        Store(app).token = "token-de-prueba-9f2a"
        Store(app).availableUpdate = update
        return MainViewModel(app).also { it.loadItems(sample) }
    }

    @Composable private fun Screen(vm: MainViewModel) {
        val state by vm.state.collectAsState()
        AppTheme { MainScreen(vm, state) {} }
    }

    @Test fun lista() {
        val vm = vm()
        paparazzi.snapshot { Screen(vm) }
    }

    @Test fun actualizacion() {
        val vm = vm(UpdateInfo(7, "1.0.7", "https://example/Controltorbox.apk"))
        paparazzi.snapshot { Screen(vm) }
    }

    @Test fun duplicados() {
        val vm = vm()
        vm.setFilter(AudioFilter.DUPS)
        vm.selectExtraCopies()
        vm.clearMessage()
        paparazzi.snapshot { Screen(vm) }
    }

    @Test fun ajustes() {
        val vm = vm()
        paparazzi.snapshot { AppTheme { SettingsScreen(vm, canGoBack = true, onBack = {}, onTokenSaved = {}) } }
    }

    @Test fun ficha() {
        val vm = vm()
        val key = "torrent:4"
        vm.store.saveAnalysis(key, Analysis(AudioStatus.ES, listOf("en", "spa"), listOf(
            AudioTrack(0, "en", "English", "E-AC3", 6, true),
            AudioTrack(1, "spa", "Castellano AC3 5.1", "AC3", 6, true),
            AudioTrack(2, "en", "Commentary", "DTS", 6, false),
        )))
        vm.rebuildRows()
        val row = vm.state.value.rows.first { it.item.key == key }
        paparazzi.snapshot {
            AppTheme {
                androidx.compose.material3.Surface {
                    ItemDetail(
                        row = row, tracks = row.analysis!!.tracks, loadingTracks = false, error = null,
                        position = 0.15f, durationMs = 2 * 3600_000L + 60_000, playing = 1,
                        onLoadTracks = {}, onPosition = {}, onPositionDone = {}, onToggle = {}, onManual = {}, onClose = {},
                    )
                }
            }
        }
    }
}
