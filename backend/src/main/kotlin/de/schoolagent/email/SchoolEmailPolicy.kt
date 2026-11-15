package de.schoolagent.email

import de.schoolagent.domain.enums.SourceType
import de.schoolagent.domain.model.Source
import de.schoolagent.domain.repo.SourceRepository
import org.springframework.stereotype.Service
import java.util.UUID

/** Shared by Gmail ingestion, instant/retried alerts and family plans. No subject keywords or AI guesses. */
@Service
class SchoolEmailPolicy(private val sources: SourceRepository) {
    fun permitsCase(id: UUID): Boolean = permits(sources.findByCaseId(id))

    fun permittedCases(ids: List<UUID>): Set<UUID> {
        if (ids.isEmpty()) return emptySet()
        val grouped = sources.findByCaseIdIn(ids).groupBy { it.caseId }
        return ids.filter { permits(grouped[it].orEmpty()) }.toSet()
    }

    private fun permits(all: List<Source>): Boolean {
        val roots = all.filter { !it.superseded && it.sourceType !in setOf(SourceType.ATTACHMENT, SourceType.WEBPAGE, SourceType.WEBPAGE_JS) }
        // Preserve mixed cases and unknown provenance; never silence real school or manually supplied evidence.
        return roots.isEmpty() || roots.any {
            it.sourceType != SourceType.EMAIL || !isDirectAccountNotice(it.sender, it.forwardedBy != null)
        }
    }

    companion object {
        // Google Account administration, distinct from Classroom, Drive sharing and school Workspace domains.
        const val GOOGLE_ACCOUNT_DOMAIN = "accounts.google.com"
        fun isDirectAccountNotice(sender: String?, forwarded: Boolean = false): Boolean =
            !forwarded && EmailContent.address(sender)?.substringAfterLast('@') == GOOGLE_ACCOUNT_DOMAIN
    }
}
