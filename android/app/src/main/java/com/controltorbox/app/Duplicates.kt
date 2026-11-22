package com.controltorbox.app

/** Detección de películas y series duplicadas (misma lógica que la versión web). */
object Duplicates {
    private val STOP_WORDS = setOf(
        "480p", "576p", "720p", "1080p", "1080i", "2160p", "4k", "uhd", "hdr", "hdr10", "dv", "sdr",
        "bluray", "blu", "bdrip", "brrip", "bdremux", "remux", "microhd", "web", "webrip", "webdl",
        "hdtv", "dvdrip", "dvd", "dvdscr", "hdrip", "screener", "cam", "ts",
        "x264", "x265", "h264", "h265", "hevc", "avc", "xvid", "10bit",
        "castellano", "spanish", "espanol", "latino", "multi", "dual", "vose", "vos", "subs",
        "ac3", "dts", "aac", "eac3", "ddp", "atmos", "truehd",
        "proper", "repack", "extended", "remastered", "unrated", "internal", "limited",
        "complete", "completa", "integral",
    )
    private val YEAR_RE = Regex("^(19[2-9]\\d|20[0-4]\\d)$")
    private val SE_RE = Regex("^s(\\d{1,2})(?:e(\\d{1,3}))?(?:e\\d{1,3})*$")
    private val SRANGE_RE = Regex("^s(\\d{1,2})s(\\d{1,2})$")
    private val X_RE = Regex("^(\\d{1,2})x(\\d{2,3})$")
    private val SEASON_WORDS = setOf("season", "temporada", "temp")
    private val EXTS = listOf(".mkv", ".mp4", ".avi", ".m4v", ".ts", ".iso", ".mov", ".wmv")

    data class ParsedName(
        val title: String,
        val year: Int? = null,
        val seasons: Set<Int> = emptySet(),
        val episode: Int? = null,
    ) {
        val isSeries: Boolean get() = seasons.isNotEmpty()
    }

    data class Group(val id: String, val label: String)

    private fun tokens(name: String): List<String> {
        var n = Language.stripAccents(name).lowercase()
        for (ext in EXTS) if (n.endsWith(ext)) n = n.dropLast(ext.length)
        n = n.replace(Regex("^\\W*www\\.[a-z0-9-]+\\.[a-z]{2,4}\\W*"), " ")
        n = n.replace(Regex("\\bs(\\d{1,2})\\s*-\\s*s(\\d{1,2})\\b"), "s$1s$2")
        return n.split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() }
    }

    fun parseName(name: String): ParsedName {
        val toks = tokens(name)
        val title = mutableListOf<String>()
        var year: Int? = null
        val seasons = mutableSetOf<Int>()
        var episode: Int? = null
        var i = 0
        while (i < toks.size) {
            val tok = toks[i]
            if (title.isNotEmpty() && YEAR_RE.matches(tok)) {
                year = tok.toInt(); break
            }
            val se = SE_RE.matchEntire(tok)
            if (se != null) {
                seasons += se.groupValues[1].toInt()
                episode = se.groupValues[2].takeIf { it.isNotEmpty() }?.toInt()
                break
            }
            val range = SRANGE_RE.matchEntire(tok)
            if (range != null) {
                val a = range.groupValues[1].toInt(); val b = range.groupValues[2].toInt()
                seasons += (minOf(a, b)..maxOf(a, b))
                break
            }
            val x = if (title.isNotEmpty()) X_RE.matchEntire(tok) else null
            if (x != null) {
                seasons += x.groupValues[1].toInt()
                episode = x.groupValues[2].toInt()
                break
            }
            if (title.isNotEmpty() && tok in SEASON_WORDS && i + 1 < toks.size && toks[i + 1].all(Char::isDigit)) {
                seasons += toks[i + 1].toInt(); break
            }
            if (title.isNotEmpty() && tok in STOP_WORDS) break
            title += tok
            i++
        }
        // Año antes de la temporada: "Show 2019 S02E03".
        if (year != null) {
            for (tok in toks.drop(i + 1)) {
                val m = SE_RE.matchEntire(tok)
                if (m != null) {
                    seasons += m.groupValues[1].toInt()
                    episode = m.groupValues[2].takeIf { it.isNotEmpty() }?.toInt()
                    break
                }
                if (tok in STOP_WORDS) break
            }
        }
        return ParsedName(title.joinToString(" "), year, seasons, episode)
    }

    private fun overlap(a: ParsedName, b: ParsedName): Boolean {
        if (a.isSeries != b.isSeries) return false
        if (!a.isSeries) return a.year == null || b.year == null || a.year == b.year
        if (a.year != null && b.year != null && a.year != b.year) return false
        if (a.seasons.intersect(b.seasons).isEmpty()) return false
        return a.episode == null || b.episode == null || a.episode == b.episode
    }

    /** Devuelve {clave del elemento -> grupo} solo para los elementos con duplicados. */
    fun find(items: List<TorboxItem>): Map<String, Group> {
        val parent = IntArray(items.size) { it }
        fun root(x: Int): Int {
            var r = x
            while (parent[r] != r) { parent[r] = parent[parent[r]]; r = parent[r] }
            return r
        }
        fun union(a: Int, b: Int) {
            val ra = root(a); val rb = root(b)
            if (ra != rb) parent[rb] = ra
        }

        val byHash = HashMap<String, Int>()
        items.forEachIndexed { i, it ->
            val h = it.hash.lowercase()
            if (h.isNotEmpty()) byHash[h]?.let { union(it, i) } ?: byHash.put(h, i)
        }

        val parsed = items.map { item ->
            var p = parseName(item.name)
            if (p.title.length < 2) item.mainVideo?.let { p = parseName(it.displayName) }
            p
        }
        val byTitle = HashMap<String, MutableList<Int>>()
        parsed.forEachIndexed { i, p -> if (p.title.length >= 2) byTitle.getOrPut(p.title) { mutableListOf() } += i }
        for (idxs in byTitle.values) {
            for (x in idxs.indices) for (y in x + 1 until idxs.size) {
                if (overlap(parsed[idxs[x]], parsed[idxs[y]])) union(idxs[x], idxs[y])
            }
        }

        val groups = items.indices.groupBy { root(it) }
        val result = HashMap<String, Group>()
        for ((r, members) in groups) {
            if (members.size < 2) continue
            val p = parsed[r]
            var label = p.title.split(" ").joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }
            if (p.year != null) label += " (${p.year})"
            if (p.isSeries) label += " · T" + p.seasons.sorted().joinToString(",")
            if (label.isBlank()) label = items[r].name
            for (m in members) result[items[m].key] = Group("g$r", label)
        }
        return result
    }
}
