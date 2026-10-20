package de.schoolagent.whatsapp

import de.schoolagent.i18n.UserLanguage
import de.schoolagent.inbox.explainAbbreviations

/** Versioned utility layouts. Line breaks belong to the approved skeleton, never parameters. */
object WaTemplates {
    const val TEST = "school_agent_connection_v3"
    fun language(lang: UserLanguage) = if (lang == UserLanguage.ES) "es" else "en_US"
    enum class Kind { ACTION, INFO, PLAN }
    data class Spec(val name: String, val language: String, val header: String, val body: String,
                    val examples: List<String>, val button: String? = null, val url: String? = null, val headerExample: String? = null,
                    val doneButton: String? = null)
    data class Message(val name: String, val language: String, val params: List<String>, val suffix: String? = null,
                       val localized: Boolean = false, val childId: String? = null, val header: String? = null)

    fun testName(lang: UserLanguage) = "${TEST}_${lang.code}"

    fun name(kind: Kind, count: Int, lang: UserLanguage = UserLanguage.EN) = "school_agent_${kind.name.lowercase()}_v5_${count}_${lang.code}"

    fun catalog(home: String): List<Spec> = UserLanguage.entries.flatMap { lang ->
        val es = lang == UserLanguage.ES
        Kind.entries.flatMap { kind -> (1..5).map { count ->
            val plan = kind == Kind.PLAN
            val section = when (kind) {
                Kind.ACTION -> if (es) "Fechas y tareas" else "Dates and tasks"
                Kind.INFO -> if (es) "Detalles" else "Details"
                Kind.PLAN -> "Agenda"
            }
            val prefix = if (plan) "${if (es) "Fechas" else "Dates"}: *{{1}}*\n\n" else "${if (es) "Carta" else "Letter"}: *{{1}}*\n\n"
            val rows = if (es) listOf("Lun 5 oct · Llevar el libro de lectura", "Mar 6 oct · Natación a las 09:00",
                "Mié 7 oct · Devolver la autorización", "Jue 8 oct · Pedir el anuario de €5", "Vie 9 oct · Entregar el pedido de ropa")
                else listOf("Mon 5 Oct · Bring the class reading book", "Tue 6 Oct · Swimming at 09:00",
                "Wed 7 Oct · Return the permission slip", "Thu 8 Oct · Order the €5 yearbook", "Fri 9 Oct · Submit the clothing order")
            Spec(name(kind, count, lang), language(lang),
                if (plan) "{{1}}" else if (kind == Kind.INFO) {
                    if (es) "Información del colegio para {{1}}" else "School information for {{1}}"
                } else { if (es) "Carta del colegio para {{1}}" else "School letter for {{1}}" },
                prefix + "*$section*\n" + (1..count).joinToString("\n") { "• {{${it + 1}}}" } + "\n\n" +
                    (if (plan) {
                        if (es) "Recordatorio del colegio solicitado. Abre tu buzón conectado para consultar las cartas originales."
                        else "Requested school reminder. Open your connected inbox for the original letters."
                    } else {
                        if (es) "Documento recibido en tu buzón del colegio conectado. Abre la carta original para consultar los detalles."
                        else "Document received in your connected school inbox. Open the original letter for full details."
                    }),
                (if (plan) listOf(if (es) "5–11 de octubre" else "5–11 October") else listOf(if (es) "Libro de lectura de la clase" else "Class reading book")) + rows.take(count),
                if (plan) { if (es) "Abrir plan" else "Open plan" } else { if (es) "Abrir carta" else "Open letter" },
                home.trimEnd('/') + "/{{1}}", if (plan) { if (es) "La próxima semana para Taylor" else "Next week for Taylor" } else "Taylor",
                if (kind == Kind.ACTION) { if (es) "Marcar como hecho" else "Mark as done" } else null)
        } } + Spec(testName(lang), language(lang), "School Agent",
            if (es) "La prueba de conexión de WhatsApp para {{1}} ha funcionado. Tus preferencias de notificaciones de School Agent están activas."
            else "Your WhatsApp connection test for {{1}} succeeded. Your selected School Agent notification preferences are active.",
            listOf("Morgan"))
    }

    private fun spec(message: Message, home: String) = catalog(home)
        .first { it.name == message.name && it.language == message.language }

    fun parameterLimits(message: Message): List<Int> = listOf(100) + List(message.params.size - 1) { 900 }

    /** Preserve logical rows. Five normal entries fit; exceptionally long details have explicit continuations. */
    fun messages(kind: Kind, lang: UserLanguage, kid: String, title: String, context: String,
                 rows: List<String>, suffix: String, localized: Boolean = false, weekly: Boolean = false,
                 abbreviations: Map<String, String?> = emptyMap()): List<Message> {
        val plan = kind == Kind.PLAN
        val seen = mutableSetOf<String>()
        fun explain(text: String) = explainAbbreviations(clean(text), abbreviations, seen)
        val prefix = listOf(slot(explain(title), 90))
        val layout = catalog("").first { it.name == name(kind, 5, lang) }
        val overhead = layout.body.replace(Regex("\\{\\{\\d+}}"), "").length
        val budget = 1024 - prefix.sumOf { it.length } - overhead - 20
        val summary = context.takeIf { !plan && it.isNotBlank() }?.let(::explain)
        val entries = rows.filter(String::isNotBlank).map(::explain).distinct()
        // An instant letter is ONE school matter: its event, deadlines and context belong together.
        // Plans contain several matters and retain their individual linked entries.
        val letterParts = (listOfNotNull(summary) + entries).distinct()
        val details = (if (plan) entries else listOf(letterParts.mapIndexed { index, part ->
            if (index < letterParts.lastIndex) part.removeSuffix(".") else part
        }.joinToString("; ")).filter(String::isNotBlank)).ifEmpty {
            listOf(if (kind == Kind.INFO) { if (lang == UserLanguage.ES && localized) "No se requiere ninguna acción." else "No action is needed." }
                else { if (lang == UserLanguage.ES && localized) "Consulta los detalles en la carta." else "See the full letter for details." })
        }.flatMap { row -> split(row, budget - 25).mapIndexed { i, part -> if (i == 0) part else "↳ Continued: $part" } }
        val pages = mutableListOf<MutableList<String>>()
        details.forEach { row ->
            val page = pages.lastOrNull()
            if (page == null || page.size == 5 || page.sumOf { it.length + 3 } + row.length + 3 > budget)
                pages += mutableListOf(row)
            else page += row
        }
        return pages.mapIndexed { index, page ->
            val part = if (pages.size > 1) " (${index + 1}/${pages.size})" else ""
            val heading = if (plan) {
                if (lang == UserLanguage.ES) "${if (weekly) "La próxima semana" else "Mañana"} para $kid"
                else "${if (weekly) "Next week" else "Tomorrow"} for $kid"
            } else kid
            Message(name(kind, page.size, lang), language(lang),
                listOf(prefix.first() + part) + page, suffix, localized,
                header = slot(heading, if (plan) 60 else 25))
        }
    }
    fun body(message: Message, home: String): String {
        val spec = spec(message, home)
        require(message.params.size == spec.examples.size) { "Template parameter count differs" }
        return Regex("\\{\\{(\\d+)}}").replace(spec.body) { message.params[it.groupValues[1].toInt() - 1] }
    }
    fun render(message: Message, home: String): String {
        val spec = spec(message, home)
        return spec.header.replace("{{1}}", message.header ?: "") + "\n\n" + body(message, home) + "\n\nSchool Agent" + (message.suffix?.let { "\n\n${spec.button}: ${home.trimEnd('/')}/$it" } ?: "")
    }
    fun clean(text: String) = text.replace(Regex("[\\r\\n\\t]+"), " ").replace(Regex(" {2,}"), " ").trim()
    fun slot(text: String, max: Int): String {
        val clean = clean(text).ifBlank { "School update" }
        if (clean.length <= max) return clean
        var end = max - 1
        if (clean[end - 1].isHighSurrogate()) end--
        return clean.take(end).trimEnd() + "…"
    }
    fun split(text: String, max: Int): List<String> {
        var rest = clean(text)
        val result = mutableListOf<String>()
        while (rest.length > max) {
            var end = rest.lastIndexOf(' ', max).takeIf { it >= max / 2 } ?: max
            if (rest[end - 1].isHighSurrogate()) end--
            result += rest.take(end)
            rest = rest.drop(end).trimStart()
        }
        if (rest.isNotBlank()) result += rest
        return result
    }
}
