package de.schoolagent.search

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/** Read-only search over persisted content. No ORM graph loading or model calls. */
@Service
class CaseSearchService(jdbc: JdbcTemplate) {
    private val sql = NamedParameterJdbcTemplate(jdbc)
    data class Hit(val caseId: UUID, val title: String, val status: String, val match: String, val score: Int)

    @Transactional(readOnly = true, timeout = 5)
    fun search(query: String, children: List<UUID>, selected: UUID?): List<Hit> {
        val terms = Regex("[\\p{L}\\p{N}]+(?:[.,][\\p{N}]+)?").findAll(query)
            .map { "'${it.value}':*" }.joinToString(" & ")
        if (terms.isEmpty()) return emptyList()
        // A sentinel keeps IN parameters valid for accounts without assigned children.
        val params = mapOf("terms" to terms, "children" to children.ifEmpty { listOf(UUID(0, 0)) },
            "kid" to selected?.toString())
        return sql.query(QUERY, params) { row, _ ->
            Hit(UUID.fromString(row.getString("id")), row.getString("title"), row.getString("status"),
                excerpt(row.getString("snippet"), query), row.getInt("importance_score"))
        }
    }

    private fun excerpt(text: String, query: String): String {
        val plain = text.replace(Regex("\\s+"), " ").trim()
        val position = Regex("[\\p{L}\\p{N}]{2,}").findAll(query).map { it.value }
            .map { plain.indexOf(it, ignoreCase = true) }.filter { it >= 0 }.minOrNull() ?: 0
        val start = (position - 55).coerceAtLeast(0)
        val end = (start + 220).coerceAtMost(plain.length)
        return (if (start > 0) "…" else "") + plain.substring(start, end) + if (end < plain.length) "…" else ""
    }

    companion object {
        private val QUERY = CaseSearchService::class.java.getResourceAsStream("/case-search.sql")!!
            .bufferedReader().use { it.readText() }
    }
}
