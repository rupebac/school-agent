package de.schoolagent.i18n

/**
 * WhatsApp copy in the recipient's language. Inbound chat is the same
 * read-only agent as the website. Explicit buttons use separate, authorized actions.
 */
object WaCopy {

    fun markedDone(lang: UserLanguage, title: String): String = when (lang) {
        UserLanguage.EN -> "Marked as done for the family: $title. You can undo this in the app."
        UserLanguage.ES -> "Marcado como hecho para la familia: $title. Puedes deshacerlo en la app."
    }

    fun alreadyDone(lang: UserLanguage, title: String): String = when (lang) {
        UserLanguage.EN -> "Already marked as done for the family: $title."
        UserLanguage.ES -> "Ya está marcado como hecho para la familia: $title."
    }

    fun doneChanged(lang: UserLanguage): String = when (lang) {
        UserLanguage.EN -> "This school matter has changed. Open the letter to review its current details before marking it done."
        UserLanguage.ES -> "Este asunto del colegio ha cambiado. Abre la carta para revisar los detalles actuales antes de marcarlo como hecho."
    }

    fun doneUnavailable(lang: UserLanguage): String = when (lang) {
        UserLanguage.EN -> "This button is unavailable for your account. Open the letter in the app."
        UserLanguage.ES -> "Este botón no está disponible para tu cuenta. Abre la carta en la app."
    }

    fun unknownSender(lang: UserLanguage): String = when (lang) {
        UserLanguage.EN -> "This number is not registered with the School Agent."
        UserLanguage.ES -> "Este número no está registrado en School Agent."
    }

    fun chatInvite(lang: UserLanguage): String = when (lang) {
        UserLanguage.EN ->
            "Hi — ask me about dates, letters, or whether the school mentioned something. For example: when is the next Elternabend? Or: anything about Weltatlas after January?"
        UserLanguage.ES ->
            "Hola — pregunta por fechas, cartas, o si el colegio mencionó algo. Por ejemplo: ¿cuándo es el próximo Elternabend? O: ¿algo sobre Weltatlas después de enero?"
    }

    fun decision(
        lang: UserLanguage,
        score: Int,
        kid: String?,
        title: String,
        deadline: String?,
        summary: String?,
        actionTitles: List<String>,
        url: String,
        infoOnly: Boolean = false,
        eventWhen: String? = null,
        eventWhere: String? = null,
    ): String {
        val es = lang == UserLanguage.ES
        val tasks = actionTitles.map { it.replace(Regex("\\s+"), " ").trim() }
            .filter { it.isNotEmpty() }.distinctBy { it.lowercase() }
        val context = summary?.takeUnless { it.trim().equals(title.trim(), ignoreCase = true) ||
            tasks.any { task -> task.equals(it.trim(), ignoreCase = true) } }
        return buildString {
            appendLine("*${listOfNotNull(kid?.takeIf { it.isNotBlank() }, title).joinToString(" · ").replace("*", "")}*")
            appendLine()
            clip(context, if (score >= 4) 500 else 280)?.let { appendLine(it); appendLine() }
            eventWhen?.takeUnless { !infoOnly && it == deadline }?.let { appendLine(if (es) "Cuándo: $it" else "When: $it") }
            deadline?.takeUnless { infoOnly }?.let { appendLine(if (es) "Fecha límite: $it" else "Due: $it") }
            eventWhere?.takeIf { it.isNotBlank() }?.let { appendLine(if (es) "Dónde: $it" else "Where: $it") }
            if (!infoOnly && tasks.isNotEmpty()) {
                appendLine()
                appendLine(if (es) "*Preparar / hacer:*" else "*To do / prepare:*")
                tasks.take(8).forEach { appendLine("• $it") }
                if (tasks.size > 8) appendLine(if (es) "Más detalles en el enlace." else "More details at the link.")
            }
            if (infoOnly) { appendLine(); appendLine(if (es) "Solo información · no requiere acción." else "Info only · no action needed.") }
            appendLine()
            append(if (es) "Abrir en School Agent:\n↗ $url" else "Open in School Agent:\n↗ $url")
        }.trim().replace(Regex("\n{3,}"), "\n\n")
    }

    private fun clip(text: String?, max: Int): String? {
        val t = text?.replace(Regex("\\s+"), " ")?.trim().orEmpty()
        if (t.isEmpty()) return null
        if (t.length <= max) return t
        val cut = t.take(max)
        val stop = cut.lastIndexOfAny(charArrayOf('.', '!', '?'))
        val word = cut.lastIndexOf(' ')
        val kept = when {
            stop >= max / 2 -> cut.take(stop + 1)
            word >= max / 2 -> cut.take(word)
            else -> cut
        }.trim()
        return "$kept…"
    }

    fun digestHeader(lang: UserLanguage, date: String): String = when (lang) {
        UserLanguage.EN -> "📬 School Agent digest — $date"
        UserLanguage.ES -> "📬 Resumen de School Agent — $date"
    }

    fun reminderHeader(lang: UserLanguage): String = when (lang) {
        UserLanguage.EN -> "⏰ Deadline check:"
        UserLanguage.ES -> "⏰ Recordatorio de plazos:"
    }

    fun reminderLine(lang: UserLanguage, overdue: Boolean, date: String, title: String): String = when (lang) {
        UserLanguage.EN -> "${if (overdue) "🔴 OVERDUE" else "🟠 due"} $date: $title"
        UserLanguage.ES -> "${if (overdue) "🔴 VENCIDO" else "🟠 vence"} $date: $title"
    }

    fun photoReceived(lang: UserLanguage, title: String, duplicate: Boolean): String = when (lang) {
        UserLanguage.EN -> if (duplicate) {
            "Already have that photo ($title)."
        } else {
            "Got the photo — I'll read it and ping you. ($title)"
        }
        UserLanguage.ES -> if (duplicate) {
            "Esa foto ya está ($title)."
        } else {
            "Foto recibida — la leo y te aviso. ($title)"
        }
    }

    fun photoFailed(lang: UserLanguage): String = when (lang) {
        UserLanguage.EN -> "Couldn't read that photo. Try again, or upload it from Inbox."
        UserLanguage.ES -> "No pude leer esa foto. Prueba otra vez, o súbela desde la bandeja."
    }
}
