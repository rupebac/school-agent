package de.schoolagent.common

/**
 * Near-duplicate titles from the reasoning model (paraphrases of the same
 * obligation). Dedup keys are exact-title, so this catches "Deadline for X"
 * vs "Deadline: X survey".
 */
object TitleSimilarity {

    private val stop = setOf(
        "a", "an", "the", "for", "of", "on", "in", "to", "by", "if", "per",
        "and", "or", "with", "at", "from", "as", "is", "are", "be",
        "deadline", "payment", "answer", "please", "eur", "euro",
    )

    fun similar(a: String, b: String, threshold: Double = 0.65): Boolean {
        val ta = tokens(a)
        val tb = tokens(b)
        if (ta.isEmpty() || tb.isEmpty()) return false
        if (ta == tb) return true
        if (ta.containsAll(tb) || tb.containsAll(ta)) return true
        val overlap = ta.intersect(tb).size
        return overlap.toDouble() / minOf(ta.size, tb.size) >= threshold
    }

    fun tokens(value: String): Set<String> =
        value.lowercase()
            .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
            .split(Regex("\\s+"))
            .map { it.trim() }
            .filter { it.length >= 2 && it !in stop }
            .toSet()
}
