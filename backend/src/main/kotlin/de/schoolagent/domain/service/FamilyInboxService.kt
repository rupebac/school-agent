package de.schoolagent.domain.service

import com.fasterxml.jackson.databind.ObjectMapper
import de.schoolagent.api.CaseNotFoundException
import de.schoolagent.domain.enums.*
import de.schoolagent.domain.model.*
import de.schoolagent.domain.repo.*
import de.schoolagent.security.KidAccessService
import org.springframework.http.HttpStatus
import org.springframework.security.core.userdetails.UserDetails
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import java.security.MessageDigest
import java.time.Clock
import java.util.UUID

/** Family completion and personal reading are intentionally separate operations. */
@Service
class FamilyInboxService(
    private val cases: SchoolCaseRepository,
    private val actions: ParentActionRepository,
    private val requirements: ChildRequirementRepository,
    private val events: SchoolEventRepository,
    private val sources: SourceRepository,
    private val reads: CaseReadRepository,
    private val changes: CaseChangeRepository,
    private val audit: AuditEntryRepository,
    private val lifecycle: CaseService,
    private val access: KidAccessService,
    private val clock: Clock,
) {
    private val json = ObjectMapper()

    fun accessible(id: UUID, user: UserDetails): SchoolCase {
        val c = cases.findById(id).orElseThrow { CaseNotFoundException(id) }
        access.requireAccess(user, c.childId)
        return c
    }

    /** Excludes translated copy, read marks, notification settings and completion. */
    fun revision(id: UUID): String {
        val facts = listOf(
            changes.findByCaseIdOrderByCreatedAtAsc(id).filter { it.targetType == ChangeTargetType.ACTION && it.changeType == ChangeType.CHANGE }.map { it.id.toString() }.sorted(),
            sources.findByCaseId(id).map { "${it.revisionIdentity ?: it.id}:${it.factHash ?: it.contentHash}" }.sorted(),
            events.findByCaseId(id).map { "${it.id}:${it.startTime}:${it.endTime}:${it.allDay}:${it.location}:${it.status}" }.sorted(),
            actions.findByCaseId(id).map { "${it.id}:${it.dueAt}:${it.required}:${it.externalUrl}" }.sorted(),
            requirements.findByCaseId(id).map { "${it.id}:${if (it.dedupKey.startsWith("req:school:")) "" else it.description}:${it.relevantOn}:${it.required}" }.sorted(),
        )
        return hash(json.writeValueAsString(facts))
    }

    fun readRevision(id: UUID, userId: UUID): String? =
        reads.findById(CaseReadId(id, userId)).orElse(null)?.revision

    fun canUndo(c: SchoolCase): Boolean = !c.schoolUpdatePending && c.status == CaseStatus.COMPLETED &&
        c.completionSnapshot?.let { runCatching { json.readTree(it).path("revision").asText() == revision(c.id) }.getOrDefault(false) } == true

    @Transactional
    fun markRead(id: UUID, user: UserDetails, read: Boolean, expectedRevision: String?) {
        val c = locked(id, user)
        if (read && c.schoolUpdatePending) throw ResponseStatusException(HttpStatus.CONFLICT, "school information changed; wait for the update to be reviewed")
        if (!c.isInfo()) throw ResponseStatusException(HttpStatus.CONFLICT, "only information can be marked read")
        val userId = access.account(user)!!.id
        val current = revision(id)
        if (read && expectedRevision != null && expectedRevision != current) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "this matter changed; refresh before marking it read")
        }
        if (read) reads.save(CaseRead(id, userId, current, clock.instant()))
        else reads.findById(CaseReadId(id, userId)).ifPresent { reads.delete(it) }
        record(c, user, if (read) "CASE_READ" else "CASE_UNREAD", mapOf("userId" to userId.toString()))
    }

    @Transactional
    fun classify(id: UUID, user: UserDetails, info: Boolean) {
        val c = locked(id, user)
        if (c.schoolUpdatePending) throw ResponseStatusException(HttpStatus.CONFLICT, "school information changed; wait for the update to be reviewed")
        if (c.isClosed()) throw ResponseStatusException(HttpStatus.CONFLICT, "undo Done before changing classification")
        val before = c.isInfo()
        c.infoOverride = info
        c.classificationReview = false
        c.updatedAt = clock.instant()
        cases.save(c)
        val target = if (info) CaseStatus.INFORMATION_ONLY else CaseStatus.ACTION_REQUIRED
        if (c.status in setOf(CaseStatus.ACTION_REQUIRED, CaseStatus.WAITING_FOR_PARENT, CaseStatus.INFORMATION_ONLY, CaseStatus.NEEDS_REVIEW)) {
            if (c.status != target) lifecycle.transition(c, target, actor(user), "parent corrected classification")
        }
        changes.save(CaseChange(caseId=id, changeType=ChangeType.CHANGE, targetType=ChangeTargetType.CASE,
            beforeJson=json.writeValueAsString(mapOf("informationOnly" to before)),
            afterJson=json.writeValueAsString(mapOf("informationOnly" to info)), createdAt=clock.instant()))
        record(c, user, "CASE_CLASSIFICATION", mapOf("informationOnly" to info))
    }

    @Transactional
    fun complete(id: UUID, user: UserDetails, expectedRevision: String? = null) {
        val c = locked(id, user)
        if (expectedRevision != null && expectedRevision != revision(id))
            throw ResponseStatusException(HttpStatus.CONFLICT, "school information changed; refresh before completing")
        if (c.schoolUpdatePending) throw ResponseStatusException(HttpStatus.CONFLICT, "school information changed; wait for the update to be reviewed")
        if (c.status == CaseStatus.COMPLETED) return
        if (c.status == CaseStatus.CANCELLED) throw ResponseStatusException(HttpStatus.CONFLICT, "cancelled matters cannot be marked Done")
        if (c.isInfo()) throw ResponseStatusException(HttpStatus.CONFLICT, "mark information as read instead")
        if (c.status !in setOf(CaseStatus.ACTION_REQUIRED, CaseStatus.WAITING_FOR_PARENT, CaseStatus.INFORMATION_ONLY, CaseStatus.NEEDS_REVIEW)) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "this matter is still being processed")
        }
        val aa = actions.findByCaseId(id).filter { it.status !in setOf(ActionStatus.COMPLETED, ActionStatus.CANCELLED) }
        val rr = requirements.findByCaseId(id).filter { it.status == RequirementStatus.OPEN }
        c.completionSnapshot = json.writeValueAsString(mapOf(
            "status" to c.status.name, "revision" to revision(id),
            "actions" to aa.associate { it.id.toString() to it.status.name },
            "requirements" to rr.map { it.id.toString() },
        ))
        aa.forEach { it.status=ActionStatus.COMPLETED; it.completedAt=clock.instant(); it.updatedAt=clock.instant(); actions.save(it) }
        rr.forEach { it.status=RequirementStatus.DONE; it.updatedAt=clock.instant(); requirements.save(it) }
        lifecycle.transition(c, CaseStatus.COMPLETED, actor(user), "marked Done for the family")
        record(c, user, "FAMILY_DONE", null)
    }

    @Transactional
    fun undo(id: UUID, user: UserDetails) {
        val c = locked(id, user)
        if (c.schoolUpdatePending) throw ResponseStatusException(HttpStatus.CONFLICT, "school information changed; wait for the update to be reviewed")
        if (c.status != CaseStatus.COMPLETED || c.completionSnapshot == null) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "no shared completion to undo")
        }
        val saved = json.readTree(c.completionSnapshot)
        if (saved.path("revision").asText() != revision(id)) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "new information arrived since completion; review this matter first")
        }
        saved.path("actions").fields().forEachRemaining { (key, value) ->
            actions.findById(UUID.fromString(key)).ifPresent { a ->
                if (a.caseId == id) { a.status=ActionStatus.valueOf(value.asText()); a.completedAt=null; a.updatedAt=clock.instant(); actions.save(a) }
            }
        }
        saved.path("requirements").forEach { key ->
            requirements.findById(UUID.fromString(key.asText())).ifPresent { r ->
                if (r.caseId == id) { r.status=RequirementStatus.OPEN; r.updatedAt=clock.instant(); requirements.save(r) }
            }
        }
        lifecycle.transition(c, CaseStatus.INVESTIGATING, actor(user), "undo shared Done")
        lifecycle.transition(c, CaseStatus.READY_FOR_REASONING, actor(user), "restore previously reviewed state")
        val previous = CaseStatus.valueOf(saved.path("status").asText())
        val target = if (previous == CaseStatus.WAITING_FOR_PARENT) CaseStatus.ACTION_REQUIRED else previous
        lifecycle.transition(c, target, actor(user), "shared completion undone")
        c.completionSnapshot=null
        cases.save(c)
        record(c, user, "FAMILY_DONE_UNDONE", null)
    }

    private fun locked(id: UUID, user: UserDetails): SchoolCase {
        val c = cases.lockById(id) ?: throw CaseNotFoundException(id)
        access.requireAccess(user, c.childId)
        return c
    }

    private fun record(c: SchoolCase, user: UserDetails, action: String, detail: Any?) {
        audit.save(AuditEntry(caseId=c.id, actor=actor(user), action=action,
            detail=detail?.let { json.writeValueAsString(it) }, createdAt=clock.instant()))
    }

    companion object {
        fun hash(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
        fun actor(user: UserDetails): Actor = Actor.MANUAL
    }
}
