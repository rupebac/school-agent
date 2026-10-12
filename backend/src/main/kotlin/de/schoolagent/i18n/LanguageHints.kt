package de.schoolagent.i18n

/** Heuristics for whether a snippet is already in a parent language. */
fun looksGerman(text: String): Boolean {
    val trimmed = text.trim()
    if (trimmed.isEmpty()) return false
    if (trimmed.any { it in "äöüÄÖÜß" }) return true
    if (COMPOUND.containsMatchIn(trimmed)) return true
    val tokens = trimmed.split(Regex("[^\\p{L}]+")).filter { it.length > 1 }.map { it.lowercase() }
    if (tokens == listOf("schullandheim")) return false
    return tokens.any { it in GERMAN_WORDS }
}

fun looksSpanish(text: String): Boolean {
    val trimmed = text.trim()
    if (trimmed.isEmpty()) return false
    if (trimmed.any { it in "áéíóúñ¿¡ÁÉÍÓÚÑ" }) return true
    val tokens = trimmed.split(Regex("[^\\p{L}]+")).filter { it.length > 2 }.map { it.lowercase() }
    return tokens.count { it in SPANISH_WORDS } >= 2
}

/** Skip an LLM round-trip when the source is already in the viewer's language. */
fun alreadyInLanguage(text: String, language: UserLanguage): Boolean = when (language) {
    UserLanguage.EN -> !looksGerman(text) && !looksSpanish(text)
    UserLanguage.ES -> looksSpanish(text) && !looksGerman(text)
}

private val COMPOUND = Regex(
    "(?i)(konzert|sportfest|wandertag|elternabend|elternbrief|klassenfahrt|schulfest|stundenplan|gottesdienst|zeugnis)$",
)

private val GERMAN_WORDS = setOf(
    "und", "für", "zum", "zur", "der", "die", "das", "den", "dem", "des",
    "ein", "eine", "am", "im", "vom", "mit", "ohne", "oder", "nicht", "bitte",
    "klasse", "schule", "eltern", "anmeldung", "ausflug", "konzert", "probe",
    "zeugnis", "ferien", "termin", "unterricht", "sportfest", "wandertag",
    "elternabend", "elternbrief", "schulfest", "klassenfahrt", "betreuung",
    "abgabe", "mitteilung", "einladung", "vertretung", "stundenplan", "aula",
    "turnhalle", "hort", "herbst", "frühling", "sommerfest",
)

private val SPANISH_WORDS = setOf(
    "para", "esta", "este", "estos", "estas", "como", "debe", "traer",
    "viernes", "lunes", "martes", "padres", "clase", "escuela", "carta",
    "excursión", "obligatorio", "opcional", "consentimiento", "llevar",
)
