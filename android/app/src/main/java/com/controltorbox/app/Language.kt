package com.controltorbox.app

import java.text.Normalizer

/** Detección del idioma del audio (misma lógica que la versión web). */
object Language {
    private val VIDEO_EXTS = setOf(
        ".mkv", ".mp4", ".avi", ".m4v", ".mov", ".wmv", ".ts", ".m2ts",
        ".mpg", ".mpeg", ".webm", ".flv", ".vob", ".iso",
    )

    private val ES_RE = Regex(
        "spanish|espa[nñ]ol|castellano|latino|latam|\\bspa\\b|\\besp\\b|\\bcast\\b",
        RegexOption.IGNORE_CASE,
    )
    private val SUBS_RE = Regex(
        "\\b(?:subs?|subtitulos?|subtitles?|subbed)\\s+(?:en\\s+)?(?:spa|esp|spanish|espa[nñ]ol|castellano)\\b",
        RegexOption.IGNORE_CASE,
    )
    private val NO_ES_RE = Regex("\\bvose\\b|\\bvos\\b|\\bvo\\b", RegexOption.IGNORE_CASE)

    private val SPANISH_CODES = setOf(
        "spa", "es", "esp", "spanish", "español", "espanol", "castellano", "latino",
        "es-es", "es-419", "es-mx",
    )
    private val UNDEFINED_CODES = setOf("", "und", "unk", "unknown", "mis", "zxx", "mul")

    private fun normalize(name: String) = name.replace(Regex("[._\\[\\]()\\-+]+"), " ")

    fun guessFromName(vararg names: String?): AudioStatus {
        var text = names.joinToString(" ") { normalize(it ?: "") }
        text = SUBS_RE.replace(text, " ")
        return when {
            ES_RE.containsMatchIn(text) -> AudioStatus.ES
            NO_ES_RE.containsMatchIn(text) -> AudioStatus.NO_ES
            else -> AudioStatus.UNKNOWN
        }
    }

    fun isVideo(fileName: String): Boolean {
        val n = fileName.lowercase()
        return VIDEO_EXTS.any { n.endsWith(it) }
    }

    fun pickMainVideo(files: List<TorboxFile>): TorboxFile? =
        files.filter { isVideo(it.name.ifBlank { it.shortName }) || isVideo(it.shortName) }
            .maxByOrNull { it.size }

    fun isSpanishCode(code: String): Boolean {
        val c = code.trim().lowercase()
        return c in SPANISH_CODES || c.startsWith("es-")
    }

    fun isUndefined(code: String) = code.trim().lowercase() in UNDEFINED_CODES

    fun classify(languages: List<String>): AudioStatus {
        if (languages.isEmpty()) return AudioStatus.UNKNOWN
        if (languages.any { isSpanishCode(it) }) return AudioStatus.ES
        if (languages.all { isUndefined(it) }) return AudioStatus.UNKNOWN
        return AudioStatus.NO_ES
    }

    /**
     * Estado de una serie a partir de sus episodios: «Español» solo si todos lo tienen;
     * «Sin español» si falta en alguno; si no, «sin comprobar».
     */
    fun combine(statuses: List<AudioStatus>): AudioStatus = when {
        statuses.isEmpty() -> AudioStatus.UNKNOWN
        statuses.all { it == AudioStatus.ES } -> AudioStatus.ES
        statuses.any { it == AudioStatus.NO_ES } -> AudioStatus.NO_ES
        else -> AudioStatus.UNKNOWN
    }

    /** Si la pista no trae idioma pero el título lo dice («Castellano AC3»), lo usamos. */
    fun trackLanguage(language: String?, label: String?): String {
        val lang = (language ?: "").trim()
        if ((lang.isEmpty() || isUndefined(lang)) && !label.isNullOrBlank() &&
            guessFromName(label) == AudioStatus.ES
        ) return "spa"
        return lang.ifEmpty { "und" }
    }

    private val NAMES = mapOf(
        "spa" to "Español", "es" to "Español", "eng" to "Inglés", "en" to "Inglés",
        "jpn" to "Japonés", "ja" to "Japonés", "fre" to "Francés", "fra" to "Francés", "fr" to "Francés",
        "ger" to "Alemán", "deu" to "Alemán", "de" to "Alemán", "ita" to "Italiano", "it" to "Italiano",
        "por" to "Portugués", "pt" to "Portugués", "kor" to "Coreano", "ko" to "Coreano",
        "chi" to "Chino", "zho" to "Chino", "zh" to "Chino", "rus" to "Ruso", "ru" to "Ruso",
        "cat" to "Catalán", "ca" to "Catalán", "und" to "Sin etiqueta",
    )

    fun displayName(code: String): String {
        val c = code.lowercase()
        if (isSpanishCode(c)) return "Español"
        return NAMES[c] ?: NAMES[c.substringBefore('-')] ?: c.uppercase()
    }

    fun stripAccents(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFKD).replace(Regex("\\p{M}+"), "")
}
