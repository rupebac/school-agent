package de.schoolagent.i18n

/**
 * Account language: English or Spanish. Anything else falls back to English.
 */
enum class UserLanguage {
    EN,
    ES,
    ;

    val code: String get() = name.lowercase()

    val englishName: String get() = when (this) {
        EN -> "English"
        ES -> "Spanish"
    }

    companion object {
        fun of(raw: String?): UserLanguage =
            if (raw?.trim()?.lowercase()?.startsWith("es") == true) ES else EN
    }
}
