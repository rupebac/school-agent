package de.schoolagent.whatsapp

/** Keep whole paragraphs/lines where possible; never silently discard a long reply. */
object WhatsAppText {
    private const val LIMIT = 3500
    private const val CONTENT_LIMIT = LIMIT - 30 // numbered continuation headings

    fun parts(text: String): List<String> {
        val cleaned = text.trim().replace(Regex("\\n{3,}"), "\n\n")
        if (cleaned.isEmpty()) return emptyList()
        if (cleaned.length <= LIMIT) return listOf(cleaned)
        val chunks = mutableListOf<String>()
        var remaining = cleaned
        while (remaining.length > CONTENT_LIMIT) {
            val cut = listOf("\n\n", "\n", " ")
                .map { remaining.lastIndexOf(it, CONTENT_LIMIT) }
                .firstOrNull { it >= CONTENT_LIMIT / 2 }
                ?: CONTENT_LIMIT.let { if (remaining[it - 1].isHighSurrogate()) it - 1 else it }
            chunks += remaining.substring(0, cut).trimEnd()
            remaining = remaining.substring(cut).trimStart()
        }
        if (remaining.isNotEmpty()) chunks += remaining
        return chunks.mapIndexed { index, chunk -> "(${index + 1}/${chunks.size})\n$chunk" }
    }
}
