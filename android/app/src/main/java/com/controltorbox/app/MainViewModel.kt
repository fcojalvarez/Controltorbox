package com.controltorbox.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset

enum class AudioFilter(val label: String) {
    NOT_ES("Sin español"), NO_ES("Sin español (confirmado)"), UNKNOWN("Desconocido"), ES("Con español"), ALL("Todo"), DUPS("Duplicados")
}

enum class SortOrder(val key: String, val label: String) {
    SIZE_DESC("size_desc", "Más grandes primero"),
    SIZE_ASC("size_asc", "Más pequeños primero"),
    DATE_DESC("date_desc", "Más recientes primero"),
    DATE_ASC("date_asc", "Más antiguos primero"),
    NAME_ASC("name_asc", "Nombre (A-Z)");

    companion object {
        fun fromKey(k: String) = entries.firstOrNull { it.key == k } ?: SIZE_DESC
    }
}

/** Un elemento con todo lo que la pantalla necesita mostrar. */
data class ItemRow(
    val item: TorboxItem,
    val status: AudioStatus,
    val source: AudioSource,
    val analysis: Analysis?,
    val duplicate: Duplicates.Group?,
    val createdAt: Instant?,
)

data class UiState(
    val hasToken: Boolean = false,
    val loading: Boolean = false,
    val rows: List<ItemRow> = emptyList(),
    val error: String? = null,
    val message: String? = null,
    val selected: Set<String> = emptySet(),
    val filter: AudioFilter = AudioFilter.NOT_ES,
    val search: String = "",
    val sort: SortOrder = SortOrder.SIZE_DESC,
    val onlyVideo: Boolean = true,
    val analyzing: String? = null,
    val update: UpdateInfo? = null,
    val updateProgress: Float? = null,
) {
    val visible: List<ItemRow> by lazy { computeVisible() }
    val totalSize: Long get() = rows.sumOf { it.item.totalSize }
    val selectedRows: List<ItemRow> get() = rows.filter { it.item.key in selected }
    val duplicateGroups: Int get() = rows.mapNotNull { it.duplicate?.id }.toSet().size

    private fun computeVisible(): List<ItemRow> {
        val q = search.trim().lowercase()
        val base = rows.filter { r ->
            (q.isEmpty() || r.item.name.lowercase().contains(q) || (r.duplicate?.label?.lowercase()?.contains(q) == true)) &&
                (filter == AudioFilter.DUPS || !onlyVideo || r.item.mainVideo != null) &&
                when (filter) {
                    AudioFilter.NOT_ES -> r.status != AudioStatus.ES
                    AudioFilter.NO_ES -> r.status == AudioStatus.NO_ES
                    AudioFilter.UNKNOWN -> r.status == AudioStatus.UNKNOWN
                    AudioFilter.ES -> r.status == AudioStatus.ES
                    AudioFilter.ALL -> true
                    AudioFilter.DUPS -> r.duplicate != null
                }
        }
        val sorted = sortRows(base, sort)
        if (filter != AudioFilter.DUPS) return sorted
        // Agrupados: grupos ordenados por su copia más grande.
        val order = sortRows(base, SortOrder.SIZE_DESC).map { it.duplicate!!.id }.distinct().withIndex()
            .associate { it.value to it.index }
        return sorted.sortedBy { order[it.duplicate!!.id] }
    }

    companion object {
        fun sortRows(rows: List<ItemRow>, sort: SortOrder): List<ItemRow> = when (sort) {
            SortOrder.SIZE_DESC -> rows.sortedByDescending { it.item.totalSize }
            SortOrder.SIZE_ASC -> rows.sortedBy { it.item.totalSize }
            SortOrder.DATE_DESC -> rows.sortedWith(compareBy<ItemRow> { it.createdAt == null }.thenByDescending { it.createdAt })
            SortOrder.DATE_ASC -> rows.sortedWith(compareBy<ItemRow> { it.createdAt == null }.thenBy { it.createdAt })
            SortOrder.NAME_ASC -> rows.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.item.name })
        }

        fun parseDate(s: String?): Instant? {
            if (s.isNullOrBlank()) return null
            return runCatching { OffsetDateTime.parse(s).toInstant() }.getOrNull()
                ?: runCatching { Instant.parse(s) }.getOrNull()
                ?: runCatching { LocalDateTime.parse(s.replace(' ', 'T').substringBefore('.')).toInstant(ZoneOffset.UTC) }.getOrNull()
        }
    }
}

class MainViewModel(app: Application) : AndroidViewModel(app) {
    val store = Store(app)
    private var items: List<TorboxItem> = emptyList()
    private val _state = MutableStateFlow(
        UiState(hasToken = store.token.isNotBlank(), sort = SortOrder.fromKey(store.sort), update = store.availableUpdate),
    )
    val state: StateFlow<UiState> = _state

    private fun api() = TorboxApi(store.token)

    fun rebuildRows() {
        val analyses = store.analyses()
        val manual = store.manualMarks()
        val dups = Duplicates.find(items)
        val rows = items.map { item ->
            val (status, source) = store.statusOf(item, analyses, manual)
            ItemRow(item, status, source, analyses[item.key], dups[item.key], UiState.parseDate(item.createdAt))
        }
        val keys = rows.map { it.item.key }.toSet()
        _state.update { it.copy(rows = rows, selected = it.selected intersect keys, hasToken = store.token.isNotBlank()) }
    }

    /** Carga elementos sin pasar por la red (para pruebas). */
    internal fun loadItems(list: List<TorboxItem>) {
        items = list
        rebuildRows()
    }

    fun refresh() {
        if (store.token.isBlank()) {
            _state.update { it.copy(hasToken = false, rows = emptyList()) }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            try {
                val (list, errors) = api().listAll()
                items = list
                rebuildRows()
                _state.update { it.copy(error = errors.takeIf { e -> e.isNotEmpty() }?.joinToString("\n")) }
                // Primera carga: memorizamos lo que hay para que los avisos solo hablen de lo nuevo.
                if (store.monitorState == null) {
                    store.monitorState = Monitor.check(null, list, store.alertSettings).second
                }
            } catch (e: TorboxException) {
                _state.update { it.copy(error = e.message) }
            } finally {
                _state.update { it.copy(loading = false) }
            }
        }
    }

    fun setFilter(f: AudioFilter) = _state.update { it.copy(filter = f) }
    fun setSearch(s: String) = _state.update { it.copy(search = s) }
    fun setOnlyVideo(v: Boolean) = _state.update { it.copy(onlyVideo = v) }
    fun setSort(s: SortOrder) {
        store.sort = s.key
        _state.update { it.copy(sort = s) }
    }

    fun toggle(key: String) = _state.update {
        it.copy(selected = if (key in it.selected) it.selected - key else it.selected + key)
    }

    fun selectAllVisible(select: Boolean) = _state.update { s ->
        val keys = s.visible.map { it.item.key }.toSet()
        s.copy(selected = if (select) s.selected + keys else s.selected - keys)
    }

    fun clearMessage() = _state.update { it.copy(message = null, error = null) }

    /** En cada grupo de duplicados conserva la copia con español (o la más grande) y selecciona el resto. */
    fun selectExtraCopies() {
        val s = _state.value
        val rank = mapOf(AudioStatus.ES to 2, AudioStatus.UNKNOWN to 1, AudioStatus.NO_ES to 0)
        var selected = s.selected
        var count = 0
        var size = 0L
        s.rows.filter { it.duplicate != null }.groupBy { it.duplicate!!.id }.values.forEach { members ->
            val ranked = members.sortedWith(
                compareByDescending<ItemRow> { rank[it.status] ?: 1 }.thenByDescending { it.item.totalSize },
            )
            selected = selected - ranked.first().item.key
            ranked.drop(1).forEach { selected = selected + it.item.key; count++; size += it.item.totalSize }
        }
        _state.update {
            it.copy(
                selected = selected,
                message = if (count == 0) "No hay copias sobrantes."
                else "Seleccionadas $count copias sobrantes (${Format.size(size)}). Revísalo antes de eliminar.",
            )
        }
    }

    fun deleteSelected() {
        val targets = _state.value.selectedRows.map { it.item }
        if (targets.isEmpty()) return
        viewModelScope.launch {
            _state.update { it.copy(loading = true) }
            val failed = mutableListOf<String>()
            val done = mutableListOf<String>()
            for (t in targets) {
                try {
                    api().delete(t.kind, t.id)
                    done += t.key
                } catch (e: TorboxException) {
                    failed += "${t.name}: ${e.message}"
                }
            }
            store.forget(done)
            items = items.filter { it.key !in done }
            rebuildRows()
            _state.update {
                it.copy(
                    loading = false,
                    selected = it.selected - done.toSet(),
                    message = if (failed.isEmpty()) "Eliminados ${done.size} elemento(s)." else null,
                    error = failed.takeIf { f -> f.isNotEmpty() }?.let { f -> "No se pudieron eliminar ${f.size}:\n${f.first()}" },
                )
            }
        }
    }

    suspend fun linkFor(item: TorboxItem): String {
        val file = item.mainVideo ?: throw TorboxException("Este elemento no tiene archivo de vídeo.")
        return api().downloadLink(item.kind, item.id, file.id)
    }

    /** Lee las pistas de audio reales de un elemento y guarda el resultado. */
    suspend fun analyze(item: TorboxItem): Analysis {
        val tracks = AudioProbe.probe(getApplication(), linkFor(item))
        val langs = tracks.map { it.language }
        val analysis = Analysis(Language.classify(langs), langs, tracks)
        store.saveAnalysis(item.key, analysis)
        rebuildRows()
        return analysis
    }

    fun analyzeSelected() {
        val targets = _state.value.selectedRows.map { it.item }.filter { it.mainVideo != null }
        if (targets.isEmpty()) return
        viewModelScope.launch {
            val errors = mutableListOf<String>()
            targets.forEachIndexed { i, item ->
                _state.update { it.copy(analyzing = "Analizando ${i + 1} de ${targets.size}…") }
                try {
                    val a = analyze(item)
                    if (a.status == AudioStatus.ES) _state.update { it.copy(selected = it.selected - item.key) }
                } catch (e: Exception) {
                    errors += "${item.name}: ${e.message}"
                }
            }
            _state.update {
                it.copy(
                    analyzing = null,
                    message = "Análisis terminado.",
                    error = errors.takeIf { e -> e.isNotEmpty() }?.let { e -> "${e.size} no se pudieron analizar:\n${e.first()}" },
                )
            }
        }
    }

    fun setManual(key: String, status: AudioStatus?) {
        store.setManual(key, status)
        rebuildRows()
    }

    /** Comprueba el token contra TorBox y lo guarda. */
    suspend fun saveToken(token: String) {
        TorboxApi(token.trim()).list(Kind.TORRENT)
        store.token = token.trim()
        store.monitorState = null
        _state.update { it.copy(hasToken = true) }
        refresh()
    }

    fun clearToken() {
        store.token = ""
        store.monitorState = null
        items = emptyList()
        _state.update { it.copy(hasToken = false, rows = emptyList(), selected = emptySet()) }
    }

    fun saveAlertSettings(s: AlertSettings) {
        store.alertSettings = s
    }

    fun reloadUpdate() = _state.update { it.copy(update = store.availableUpdate) }

    /** Comprobación manual de actualizaciones (botón en Ajustes). Devuelve un texto para mostrar. */
    suspend fun checkForUpdate(): String = try {
        val u = Updater().check(BuildConfig.VERSION_CODE)
        store.availableUpdate = u
        reloadUpdate()
        if (u == null) "Tienes la última versión (${BuildConfig.VERSION_NAME})." else "Hay una versión nueva: ${u.versionName}."
    } catch (e: TorboxException) {
        e.message ?: "No se pudo comprobar"
    }

    /** Descarga la actualización y abre el instalador de Android. */
    fun installUpdate() {
        val info = _state.value.update ?: return
        val app = getApplication<Application>()
        if (!Updater.canInstall(app)) {
            Updater.openInstallPermission(app)
            _state.update { it.copy(message = "Permite «Instalar apps desconocidas» para Controltorbox y vuelve a pulsar Actualizar.") }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(updateProgress = 0f) }
            try {
                val apk = Updater().download(app, info) { p -> _state.update { it.copy(updateProgress = p) } }
                Updater.install(app, apk)
            } catch (e: TorboxException) {
                _state.update { it.copy(error = e.message) }
            } finally {
                _state.update { it.copy(updateProgress = null) }
            }
        }
    }
}
