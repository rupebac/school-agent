package de.schoolagent.briefing

import de.schoolagent.domain.model.SchoolCase
import de.schoolagent.domain.repo.SchoolCaseRepository
import de.schoolagent.email.SchoolEmailPolicy
import de.schoolagent.inbox.CaseRelevance
import de.schoolagent.inbox.CaseRelevanceService
import org.springframework.stereotype.Service
import java.util.UUID

/** One audience and eligibility boundary for conversational and scheduled plans.
 * Applies access before loading provenance; never calls AI or changes case state.
 * Priority and date windows remain the responsibility of the particular plan.
 */
@Service
class BriefingCaseSelector(
    private val cases: SchoolCaseRepository,
    private val relevance: CaseRelevanceService,
    private val email: SchoolEmailPolicy,
) {
    data class Snapshot(
        val all: Map<UUID, SchoolCase>,
        val visibility: Map<UUID, CaseRelevance>,
        val permitted: Set<UUID>,
        val active: Map<UUID, SchoolCase>,
    )

    fun read(allowedChildren: Collection<UUID>): Snapshot {
        if (allowedChildren.isEmpty()) return Snapshot(emptyMap(), emptyMap(), emptySet(), emptyMap())
        val allowed = allowedChildren.toSet()
        // Unassigned cases are family-shared, matching the inbox access contract.
        val scoped = cases.findAll().filter { it.childId == null || it.childId in allowed }
        val visibility = relevance.current(scoped)
        val permitted = email.permittedCases(scoped.map { it.id })
        val active = scoped.filter {
            it.id in permitted && visibility[it.id]?.archived != true &&
                !it.isClosed() && !it.schoolUpdatePending && !it.muted
        }.associateBy { it.id }
        return Snapshot(scoped.associateBy { it.id }, visibility, permitted, active)
    }
}
