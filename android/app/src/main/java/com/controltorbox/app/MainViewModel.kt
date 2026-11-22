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

enum class LibraryFilter(val label: String) {
    ALL("Todo"), NO_ES("Sin español"), UNKNOWN("Sin comprobar"), DUPS("Duplicados"), ES("Con español")
}

enum class SortOrder(val key: String, val label: String) {
    DATE_DESC("date_desc", "Más recientes"),
    DATE_ASC("date_asc", "Más antiguos"),
    SIZE_DESC("size_desc", "Más grandes"),
    SIZE_ASC("size_asc", "Más pequeños"),
    NAME_ASC("name_asc", "Nombre (A-Z)");

    companion object {
        fun fromKey(k: String) = entries.firstOrNull { it.key == k } ?: DATE_DESC
    }
}

enum class Decision { KEEP, DELETE }

/** Un elemento con todo lo que la interfaz necesita mostrar. */
data class ItemRow(
    val item: TorboxItem,
    val status: AudioStatus,
    val source: AudioSource,
    val analysis: Analysis?,
    val duplicate: Duplicates.Group?,
    val createdAt: Instant?,
    val kept: Boolean = false,
) {
    val key: String get() = item.key
    val title: String = Titles.clean(item.name)
    val quality: String = Titles.quality(item.name)
    val size: Long get() = item.totalSize
    val hasVideo: Boolean get() = item.mainVideo != null
}

data class UiState(
    val hasToken: Boolean = false,
    val loading: Boolean = false,
    val rows: List<ItemRow> = emptyList(),
    val error: String? = null,
    val message: String? = null,
    val selected: Set<String> = emptySet(),
    val filter: LibraryFilter = LibraryFilter.ALL,
    val search: String = "",
    val sort: SortOrder = SortOrder.DATE_DESC,
    val analyzing: String? = null,
    val update: UpdateInfo? = null,
    val updateProgress: Float? = null,
    val updateDismissed: Boolean = false,
    /** Decisiones de la pantalla «Revisar sin español» (se aplican al confirmar). */
    val decisions: Map<String, Decision> = emptyMap(),
    /** Copia elegida para conservar en cada grupo de duplicados (por id de grupo). */
    val dupKeep: Map<String, String> = emptyMap(),
    val storageLimitGb: Int = 500,
    val notifyStorage: Boolean = true,
    val planName: String? = null,
    /** Límite de la cuenta de TorBox en bytes (null = desconocido). */
    val accountLimit: Long? = null,
    /** true si el límite de la cuenta lo dio TorBox; false si lo pusiste tú. */
    val accountLimitFromTorbox: Boolean = false,
) {
    /** Tu aviso de espacio en bytes (null si está desactivado). */
    val alertLimit: Long? get() = if (notifyStorage && storageLimitGb > 0) storageLimitGb.toLong() * GB else null

    val totalSize: Long get() = rows.sumOf { it.size }
    fun sizeOf(kind: Kind) = rows.filter { it.item.kind == kind }.sumOf { it.size }
    fun row(key: String) = rows.firstOrNull { it.key == key }

    /** Pendientes de revisar: vídeos sin español confirmado que no has decidido conservar. */
    val reviewRows: List<ItemRow> by lazy {
        rows.filter { it.hasVideo && it.status != AudioStatus.ES && !it.kept }
            .sortedWith(compareBy<ItemRow> { if (it.status == AudioStatus.NO_ES) 0 else 1 }.thenByDescending { it.size })
    }
    val noEsCount: Int get() = reviewRows.count { it.status == AudioStatus.NO_ES }
    val unknownRows: List<ItemRow> get() = reviewRows.filter { it.status == AudioStatus.UNKNOWN }

    /** Grupos de duplicados con la copia a conservar (la elegida o la sugerida). */
    val duplicateGroups: List<DupGroup> by lazy {
        rows.filter { it.duplicate != null }.groupBy { it.duplicate!!.id }.map { (id, members) ->
            val ranked = members.sortedWith(
                compareByDescending<ItemRow> { RANK[it.status] ?: 1 }.thenByDescending { it.size },
            )
            val keep = dupKeep[id]?.takeIf { k -> members.any { it.key == k } } ?: ranked.first().key
            DupGroup(id, members.first().duplicate!!.label, ranked, keep)
        }.sortedByDescending { g -> g.members.sumOf { it.size } }
    }
    val duplicateFreeable: Long get() = duplicateGroups.sumOf { it.freeable }

    val recent: List<ItemRow> get() = sortRows(rows, SortOrder.DATE_DESC).filter { it.createdAt != null }.take(3)

    val libraryRows: List<ItemRow> by lazy {
        val q = search.trim().lowercase()
        sortRows(
            rows.filter { r ->
                (q.isEmpty() || r.title.lowercase().contains(q) || r.item.name.lowercase().contains(q)) &&
                    when (filter) {
                        LibraryFilter.ALL -> true
                        LibraryFilter.NO_ES -> r.status == AudioStatus.NO_ES
                        LibraryFilter.UNKNOWN -> r.status == AudioStatus.UNKNOWN && r.hasVideo
                        LibraryFilter.DUPS -> r.duplicate != null
                        LibraryFilter.ES -> r.status == AudioStatus.ES
                    }
            },
            sort,
        )
    }

    fun countFor(f: LibraryFilter) = when (f) {
        LibraryFilter.ALL -> rows.size
        LibraryFilter.NO_ES -> rows.count { it.status == AudioStatus.NO_ES }
        LibraryFilter.UNKNOWN -> rows.count { it.status == AudioStatus.UNKNOWN && it.hasVideo }
        LibraryFilter.DUPS -> rows.count { it.duplicate != null }
        LibraryFilter.ES -> rows.count { it.status == AudioStatus.ES }
    }

    val selectedRows: List<ItemRow> get() = rows.filter { it.key in selected }
    val showUpdateDock: Boolean get() = update != null && !updateDismissed

    companion object {
        const val GB = 1024L * 1024 * 1024
        val RANK = mapOf(AudioStatus.ES to 2, AudioStatus.UNKNOWN to 1, AudioStatus.NO_ES to 0)

        fun sortRows(rows: List<ItemRow>, sort: SortOrder): List<ItemRow> = when (sort) {
            SortOrder.SIZE_DESC -> rows.sortedByDescending { it.size }
            SortOrder.SIZE_ASC -> rows.sortedBy { it.size }
            SortOrder.DATE_DESC -> rows.sortedWith(compareBy<ItemRow> { it.createdAt == null }.thenByDescending { it.createdAt })
            SortOrder.DATE_ASC -> rows.sortedWith(compareBy<ItemRow> { it.createdAt == null }.thenBy { it.createdAt })
            SortOrder.NAME_ASC -> rows.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.title })
        }

        fun parseDate(s: String?): Instant? {
            if (s.isNullOrBlank()) return null
            return runCatching { OffsetDateTime.parse(s).toInstant() }.getOrNull()
                ?: runCatching { Instant.parse(s) }.getOrNull()
                ?: runCatching { LocalDateTime.parse(s.replace(' ', 'T').substringBefore('.')).toInstant(ZoneOffset.UTC) }.getOrNull()
        }
    }
}

data class DupGroup(val id: String, val label: String, val members: List<ItemRow>, val keepKey: String) {
    val toDelete: List<ItemRow> get() = members.filter { it.key != keepKey }
    val freeable: Long get() = toDelete.sumOf { it.size }
}

/** Copia los límites (cuenta y aviso) desde lo guardado. */
fun UiState.withLimits(store: Store): UiState {
    val detected = store.accountLimitDetected
    val manual = store.accountLimitManualGb.takeIf { it > 0 }?.let { it.toLong() * UiState.GB }
    val settings = store.alertSettings
    return copy(
        storageLimitGb = settings.storageLimitGb,
        notifyStorage = settings.notifyStorage,
        planName = AccountInfo(store.accountPlan, null).planName,
        accountLimit = detected ?: manual,
        accountLimitFromTorbox = detected != null,
    )
}

class MainViewModel(app: Application) : AndroidViewModel(app) {
    val store = Store(app)
    private var items: List<TorboxItem> = emptyList()
    private val _state = MutableStateFlow(
        UiState(
            hasToken = store.token.isNotBlank(),
            sort = SortOrder.fromKey(store.sort),
            update = store.availableUpdate,
        ).withLimits(store),
    )
    val state: StateFlow<UiState> = _state

    private fun api() = TorboxApi(store.token)

    fun rebuildRows() {
        val analyses = store.analyses()
        val manual = store.manualMarks()
        val kept = store.kept
        val dups = Duplicates.find(items)
        val rows = items.map { item ->
            val (status, source) = store.statusOf(item, analyses, manual)
            ItemRow(item, status, source, analyses[item.key], dups[item.key], UiState.parseDate(item.createdAt), item.key in kept)
        }
        val keys = rows.map { it.key }.toSet()
        _state.update {
            it.copy(
                rows = rows,
                selected = it.selected intersect keys,
                decisions = it.decisions.filterKeys { k -> k in keys },
                hasToken = store.token.isNotBlank(),
            ).withLimits(store)
        }
    }

    /** Carga elementos sin pasar por la red (para pruebas y capturas). */
    internal fun loadItems(list: List<TorboxItem>) {
        items = list
        rebuildRows()
    }

    internal fun setStateForPreview(transform: (UiState) -> UiState) = _state.update(transform)

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
                // Plan y límite de la cuenta (si falla, se mantiene lo último guardado).
                try {
                    val acc = api().account()
                    store.accountPlan = acc.plan
                    store.accountLimitDetected = acc.storageLimitBytes
                    _state.update { it.withLimits(store) }
                } catch (e: TorboxException) { /* no es imprescindible */ }
                // Primera carga: memorizamos lo que hay para que los avisos solo hablen de lo nuevo.
                if (store.monitorState == null) {
                    store.monitorState = Monitor.check(null, list, store.alertSettings).second
                }
            } catch (e: TorboxException) {
                _state.update { it.copy(error = e.message) }
            } finally {
                _state.update { it.copy(loading = false, update = store.availableUpdate) }
            }
        }
    }

    // -- Biblioteca -----------------------------------------------------------
    fun setFilter(f: LibraryFilter) = _state.update { it.copy(filter = f) }
    fun setSearch(s: String) = _state.update { it.copy(search = s) }
    fun setSort(s: SortOrder) {
        store.sort = s.key
        _state.update { it.copy(sort = s) }
    }

    fun toggleSelected(key: String) = _state.update {
        it.copy(selected = if (key in it.selected) it.selected - key else it.selected + key)
    }

    fun clearSelection() = _state.update { it.copy(selected = emptySet()) }
    fun clearMessage() = _state.update { it.copy(message = null, error = null) }

    // -- Revisar sin español ----------------------------------------------------
    fun decide(key: String, d: Decision) = _state.update {
        val cur = it.decisions[key]
        it.copy(decisions = if (cur == d) it.decisions - key else it.decisions + (key to d))
    }

    /** Aplica las decisiones: borra las marcadas y recuerda las conservadas. */
    fun applyReview() {
        val s = _state.value
        val toKeep = s.decisions.filterValues { it == Decision.KEEP }.keys
        val toDelete = s.decisions.filterValues { it == Decision.DELETE }.keys
        if (toKeep.isNotEmpty()) store.kept = store.kept + toKeep
        _state.update { it.copy(decisions = emptyMap()) }
        if (toDelete.isEmpty()) {
            rebuildRows()
            _state.update { it.copy(message = "Guardado: ${toKeep.size} conservado(s).") }
        } else {
            delete(toDelete)
        }
    }

    // -- Duplicados -------------------------------------------------------------
    fun keepCopy(groupId: String, key: String) = _state.update { it.copy(dupKeep = it.dupKeep + (groupId to key)) }

    fun deleteDuplicateCopies() {
        val keys = _state.value.duplicateGroups.flatMap { g -> g.toDelete.map { it.key } }
        _state.update { it.copy(dupKeep = emptyMap()) }
        delete(keys)
    }

    // -- Borrado ----------------------------------------------------------------
    fun deleteSelected() = delete(_state.value.selected)

    fun delete(keys: Collection<String>) {
        val targets = items.filter { it.key in keys }
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
                    failed += "${Titles.clean(t.name)}: ${e.message}"
                }
            }
            store.forget(done)
            items = items.filter { it.key !in done }
            rebuildRows()
            val freed = targets.filter { it.key in done }.sumOf { it.totalSize }
            _state.update {
                it.copy(
                    loading = false,
                    selected = it.selected - done.toSet(),
                    message = if (done.isNotEmpty()) "Borrados ${done.size} · liberados ${Format.size(freed)}" else null,
                    error = failed.takeIf { f -> f.isNotEmpty() }?.let { f -> "No se pudieron borrar ${f.size}:\n${f.first()}" },
                )
            }
        }
    }

    // -- Audio --------------------------------------------------------------------
    suspend fun linkFor(item: TorboxItem, fileId: Long? = null): String {
        val id = fileId ?: item.mainVideo?.id ?: throw TorboxException("Este elemento no tiene archivo de vídeo.")
        return api().downloadLink(item.kind, item.id, id)
    }

    /**
     * Lee las pistas de audio reales y guarda el resultado. En una serie con varios
     * episodios se lee cada uno; [onProgress] recibe (episodio actual, total).
     */
    suspend fun analyze(item: TorboxItem, onProgress: (Int, Int) -> Unit = { _, _ -> }): Analysis {
        val videos = item.videoFiles
        val analysis = if (videos.size <= 1) {
            onProgress(1, 1)
            val tracks = AudioProbe.probe(getApplication(), linkFor(item))
            val langs = tracks.map { it.language }
            Analysis(Language.classify(langs), langs, tracks)
        } else {
            val episodes = videos.mapIndexed { i, f ->
                onProgress(i + 1, videos.size)
                try {
                    val tracks = AudioProbe.probe(getApplication(), linkFor(item, f.id))
                    val langs = tracks.map { it.language }
                    EpisodeAnalysis(f.id, f.displayName, Language.classify(langs), langs, tracks)
                } catch (e: Exception) {
                    EpisodeAnalysis(f.id, f.displayName, AudioStatus.UNKNOWN, emptyList(), emptyList(), e.message ?: "error")
                }
            }
            if (episodes.all { it.error != null }) throw TorboxException("No se pudo leer ningún episodio: ${episodes.first().error}")
            Analysis(
                Language.combine(episodes.map { it.status }),
                episodes.flatMap { it.languages }.distinct(),
                episodes.firstOrNull { it.error == null }?.tracks.orEmpty(),
                episodes,
            )
        }
        store.saveAnalysis(item.key, analysis)
        rebuildRows()
        return analysis
    }

    /** Analiza todos los que tienen el idioma sin comprobar. */
    fun analyzeUnknown() {
        val targets = _state.value.unknownRows.map { it.item }
        if (targets.isEmpty() || _state.value.analyzing != null) return
        viewModelScope.launch {
            var errors = 0
            var withEs = 0
            targets.forEachIndexed { i, item ->
                _state.update { it.copy(analyzing = "Analizando ${i + 1} de ${targets.size}…") }
                try {
                    val a = analyze(item) { ep, n ->
                        if (n > 1) _state.update { it.copy(analyzing = "Analizando ${i + 1} de ${targets.size} · episodio $ep de $n…") }
                    }
                    if (a.status == AudioStatus.ES) withEs++
                } catch (e: Exception) {
                    errors++
                }
            }
            _state.update {
                it.copy(
                    analyzing = null,
                    message = "Analizados ${targets.size - errors}: $withEs con español" +
                        if (errors > 0) " · $errors no se pudieron leer" else "",
                )
            }
        }
    }

    fun setManual(key: String, status: AudioStatus?) {
        store.setManual(key, status)
        rebuildRows()
    }

    // -- Ajustes ------------------------------------------------------------------
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
        _state.update { it.withLimits(store) }
    }

    fun setAccountLimitGb(gb: Int) {
        store.accountLimitManualGb = gb.coerceAtLeast(0)
        _state.update { it.withLimits(store) }
    }

    // -- Actualizaciones ----------------------------------------------------------
    fun reloadUpdate() = _state.update { it.copy(update = store.availableUpdate, updateDismissed = false) }
    fun dismissUpdate() = _state.update { it.copy(updateDismissed = true) }

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
            _state.update { it.copy(message = "Permite «Instalar apps desconocidas» para Controltorbox y vuelve a pulsar Instalar.") }
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
