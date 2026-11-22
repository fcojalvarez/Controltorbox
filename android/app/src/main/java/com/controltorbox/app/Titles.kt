package com.controltorbox.app

/** Nombre legible a partir del nombre técnico de la release. */
object Titles {
    private val QUALITY = listOf(
        Regex("\\b(2160p|4k|uhd)\\b", RegexOption.IGNORE_CASE) to "4K",
        Regex("\\b1080[pi]\\b", RegexOption.IGNORE_CASE) to "1080p",
        Regex("\\b720p\\b", RegexOption.IGNORE_CASE) to "720p",
        Regex("\\b(480p|576p|dvdrip)\\b", RegexOption.IGNORE_CASE) to "SD",
    )
    private val SOURCE = listOf(
        Regex("\\bremux\\b", RegexOption.IGNORE_CASE) to "Remux",
        Regex("\\b(bluray|blu-ray|bdrip|brrip)\\b", RegexOption.IGNORE_CASE) to "BluRay",
        Regex("\\bweb[ ._-]?(dl|rip)?\\b", RegexOption.IGNORE_CASE) to "WEB",
        Regex("\\bhdtv\\b", RegexOption.IGNORE_CASE) to "HDTV",
    )
    private val SMALL = setOf("de", "del", "la", "el", "los", "las", "y", "of", "the", "and", "a", "en", "to")

    private fun titleCase(t: String): String = t.split(" ").filter { it.isNotBlank() }.mapIndexed { i, w ->
        if (i > 0 && w in SMALL) w else w.replaceFirstChar { it.uppercase() }
    }.joinToString(" ")

    /** «Oppenheimer (2023)», «The Bear · T3», «Shogun · T1 E1». */
    fun clean(name: String): String {
        val p = Duplicates.parseName(name)
        if (p.title.length < 2) return name
        var out = titleCase(p.title)
        if (p.isSeries) {
            val seasons = p.seasons.sorted()
            out += if (seasons.size > 1) " · T${seasons.first()}–T${seasons.last()}" else " · T${seasons.first()}"
            if (p.episode != null) out += " E${p.episode}"
        } else if (p.year != null) {
            out += " (${p.year})"
        }
        return out
    }

    /** «4K HDR», «1080p BluRay», «VOSE»… (vacío si no se reconoce nada). */
    fun quality(name: String): String {
        val parts = mutableListOf<String>()
        QUALITY.firstOrNull { it.first.containsMatchIn(name) }?.let { parts += it.second }
        if (Regex("\\bhdr(10)?\\b|\\bdv\\b", RegexOption.IGNORE_CASE).containsMatchIn(name)) parts += "HDR"
        SOURCE.firstOrNull { it.first.containsMatchIn(name) }?.let { parts += it.second }
        if (Regex("\\bvose\\b", RegexOption.IGNORE_CASE).containsMatchIn(name)) parts += "VOSE"
        return parts.joinToString(" ")
    }
}
