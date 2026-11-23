package com.controltorbox.app

import android.app.Application
import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.resources.NightMode
import org.junit.Rule
import org.junit.Test
import java.time.Instant
import java.time.temporal.ChronoUnit

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
    private val now = Instant.now()
    private fun ago(days: Long) = now.minus(days, ChronoUnit.DAYS).toString()
    private fun item(kind: Kind, id: Long, name: String, gb: Double, date: String?, files: List<String> = listOf("$name.mkv")) =
        TorboxItem(kind, id, name, (gb * GB).toLong(), date, "", true,
            files.mapIndexed { i, f -> TorboxFile(id * 10 + i, f, f, (gb * GB / files.size).toLong()) })

    private fun downloading(kind: Kind, id: Long, name: String, gb: Double, progress: Float, speedMb: Long, seeds: Int, state: String = "downloading") =
        TorboxItem(kind, id, name, (gb * GB).toLong(), ago(0), "", false, emptyList(), state, progress, speedMb * 1024 * 1024, 1500, seeds, 4, null, null)

    private fun withDownloads(vm: MainViewModel) {
        vm.loadItems((sample + listOf(
            downloading(Kind.TORRENT, 30, "Severance.S02E05.1080p.WEB", 2.4, 0.62f, 8, 23),
            downloading(Kind.TORRENT, 31, "Old.Movie.1999.720p.BluRay", 1.1, 0.12f, 0, 0, "stalled (no seeds)"),
            downloading(Kind.USENET, 32, "Andor.S02E01.2160p", 6.0, 0.35f, 22, 0),
        )).map { if (it.id == 4L) it.copy(expiresAt = now.plus(2, ChronoUnit.DAYS).toString()) else if (it.id == 3L) it.copy(expiresAt = now.plus(5, ChronoUnit.DAYS).toString()) else it })
        vm.setStateForPreview { it.copy(queued = listOf(
            QueuedItem(Kind.TORRENT, 40, "Dune.Prophecy.S01.1080p", ago(1), "x"),
            QueuedItem(Kind.USENET, 41, "The.Last.of.Us.S02E03.2160p", ago(0), "y"),
        )) }
    }

    @Test fun inicio_con_descargas() {
        val vm = vm()
        withDownloads(vm)
        paparazzi.snapshot { Root(vm, Nav()) }
    }

    @Test fun descargas() {
        val vm = vm()
        withDownloads(vm)
        val nav = Nav().apply { open(Route.Downloads) }
        paparazzi.snapshot { Root(vm, nav) }
    }

    @Test fun biblioteca_caduca() {
        val vm = vm()
        withDownloads(vm)
        vm.setFilter(LibraryFilter.EXPIRING)
        paparazzi.snapshot { Root(vm, Nav(Tab.LIBRARY)) }
    }

    @Test fun ficha_atascada() {
        val vm = vm()
        withDownloads(vm)
        val row = vm.state.value.row("torrent:31")!!
        paparazzi.snapshot { AppTheme { DetailContent(row, false, null, PlaybackUi(), {}, {}, {}, {}, {}, {}, {}) } }
    }

    private val sample = listOf(
        item(Kind.TORRENT, 1, "Dune.Part.Two.2024.2160p.WEB-DL.Castellano.AC3", 18.0, ago(51)),
        item(Kind.TORRENT, 2, "The.Bear.S03.1080p.WEB.h264-ETHEL", 9.0, ago(2),
            listOf("The.Bear.S03E01.mkv", "The.Bear.S03E02.mkv", "The.Bear.S03E03.mkv")),
        item(Kind.TORRENT, 3, "Shogun.2024.S01E01.VOSE.1080p", 3.2, ago(311)),
        item(Kind.TORRENT, 4, "Oppenheimer.2023.1080p.BluRay.x264", 14.0, ago(155)),
        item(Kind.USENET, 9, "Oppenheimer (2023) [4K HDR][Dual]", 32.0, ago(20)),
        item(Kind.USENET, 10, "The.Bear.S03E02.720p.HDTV", 1.2, ago(4)),
    )

    private fun vm(update: UpdateInfo? = null, token: String = "token-de-prueba-9f2a", accountGb: Int = 1000): MainViewModel {
        val app = TestApp(paparazzi.context)
        val store = Store(app)
        store.token = token
        store.accountPlan = 2
        store.accountLimitManualGb = accountGb
        store.availableUpdate = update
        store.saveAnalysis("usenet:9", Analysis(AudioStatus.ES, listOf("spa", "en"), listOf(
            AudioTrack(0, "spa", "Castellano", "E-AC3", 6, true), AudioTrack(1, "en", "", "TrueHD", 8, false),
        )))
        return MainViewModel(app).also { it.loadItems(sample) }
    }

    @Composable private fun Root(vm: MainViewModel, nav: Nav, dark: Boolean = false) {
        val state by vm.state.collectAsState()
        AppTheme(dark = dark) { AppRoot(vm, state, nav) }
    }

    @Test fun inicio_con_actualizacion() {
        val vm = vm(UpdateInfo(99, "1.0.12", "https://example/Controltorbox.apk"))
        paparazzi.snapshot { Root(vm, Nav()) }
    }

    @Test fun inicio_sin_limite_de_cuenta() {
        val vm = vm(accountGb = 0)
        paparazzi.snapshot { Root(vm, Nav()) }
    }

    @Test fun inicio_aviso_superado() {
        val vm = vm()
        vm.saveAlertSettings(AlertSettings(storageLimitGb = 50))
        paparazzi.snapshot { Root(vm, Nav()) }
    }

    @Test fun inicio_oscuro() {
        val vm = vm()
        paparazzi.unsafeUpdateConfig(deviceConfig = DeviceConfig.PIXEL_5.copy(nightMode = NightMode.NIGHT))
        paparazzi.snapshot { Root(vm, Nav(), dark = true) }
    }

    @Test fun revisar() {
        val vm = vm()
        vm.decide("torrent:3", Decision.DELETE)
        vm.decide("torrent:4", Decision.DELETE)
        vm.decide("torrent:2", Decision.KEEP)
        val nav = Nav().apply { open(Route.Review) }
        paparazzi.snapshot { Root(vm, nav) }
    }

    @Test fun duplicados() {
        val vm = vm()
        val nav = Nav().apply { open(Route.Dups) }
        paparazzi.snapshot { Root(vm, nav) }
    }

    @Test fun biblioteca() {
        val vm = vm()
        paparazzi.snapshot { Root(vm, Nav(Tab.LIBRARY)) }
    }

    @Test fun biblioteca_seleccion() {
        val vm = vm()
        vm.toggleSelected("torrent:3"); vm.toggleSelected("usenet:10")
        paparazzi.snapshot { Root(vm, Nav(Tab.LIBRARY)) }
    }

    @Test fun ficha() {
        val vm = vm()
        val row = vm.state.value.row("usenet:9")!!
        paparazzi.snapshot {
            AppTheme {
                DetailContent(
                    row = row, loadingTracks = false, error = null,
                    playback = PlaybackUi(position = 0.15f, durationMs = 2 * 3600_000L + 60_000, playing = 0, currentMs = 1_101_000, clipStartMs = 1_089_000),
                    onBack = {}, onLoadTracks = {}, onPosition = {}, onPositionDone = {}, onToggle = {}, onManual = {}, onDelete = {},
                )
            }
        }
    }

    @Test fun ficha_serie() {
        val vm = vm()
        val es = AudioTrack(0, "spa", "Castellano", "E-AC3", 6, true)
        val en = AudioTrack(1, "en", "", "E-AC3", 6, true)
        vm.store.saveAnalysis("torrent:2", Analysis(AudioStatus.NO_ES, listOf("spa", "en"), listOf(es, en), listOf(
            EpisodeAnalysis(20, "The.Bear.S03E01.mkv", AudioStatus.ES, listOf("spa", "en"), listOf(es, en)),
            EpisodeAnalysis(21, "The.Bear.S03E02.mkv", AudioStatus.NO_ES, listOf("en"), listOf(en.copy(index = 0))),
            EpisodeAnalysis(22, "The.Bear.S03E03.mkv", AudioStatus.ES, listOf("spa", "en"), listOf(es, en)),
        )))
        vm.rebuildRows()
        val row = vm.state.value.row("torrent:2")!!
        paparazzi.snapshot {
            AppTheme {
                DetailContent(row, false, null, PlaybackUi(), {}, {}, {}, {}, {}, {}, {}, selectedEpisode = 21)
            }
        }
    }

    @Test fun ficha_serie_analizada_antes() {
        val vm = vm()
        vm.store.saveAnalysis("torrent:2", Analysis(AudioStatus.ES, listOf("spa"), listOf(AudioTrack(0, "spa", "Castellano", "AC3", 6, true))))
        vm.rebuildRows()
        val row = vm.state.value.row("torrent:2")!!
        paparazzi.snapshot { AppTheme { DetailContent(row, false, null, PlaybackUi(), {}, {}, {}, {}, {}, {}, {}) } }
    }

    @Test fun ficha_sin_video() {
        val vm = vm()
        vm.loadItems(listOf(item(Kind.WEBDL, 50, "Serie.S01.COMPLETA", 4.0, ago(1), files = listOf("Serie.S01.part1.rar", "Serie.S01.part2.rar"))))
        val row = vm.state.value.row("webdl:50")!!
        paparazzi.snapshot { AppTheme { DetailContent(row, false, null, PlaybackUi(), {}, {}, {}, {}, {}, {}, {}) } }
    }

    @Test fun ficha_sin_analizar() {
        val vm = vm()
        val row = vm.state.value.row("torrent:2")!!
        paparazzi.snapshot {
            AppTheme {
                DetailContent(row, false, null, PlaybackUi(), {}, {}, {}, {}, {}, {}, {})
            }
        }
    }

    @Test fun ajustes() {
        val vm = vm(UpdateInfo(99, "1.0.12", "https://example/Controltorbox.apk"))
        paparazzi.snapshot { Root(vm, Nav(Tab.SETTINGS)) }
    }

    @Test fun bienvenida() {
        val vm = vm(token = "")
        paparazzi.snapshot { Root(vm, Nav()) }
    }
}
