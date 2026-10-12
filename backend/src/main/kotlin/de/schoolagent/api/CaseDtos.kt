package de.schoolagent.api

import de.schoolagent.domain.model.CaseChange
import de.schoolagent.domain.model.CaseStatusEntry
import de.schoolagent.domain.model.ChildRequirement
import de.schoolagent.domain.model.Evidence
import de.schoolagent.domain.model.ExternalForm
import de.schoolagent.domain.model.Investigation
import de.schoolagent.domain.model.InvestigationStep
import de.schoolagent.domain.model.ParentAction
import de.schoolagent.domain.model.SchoolCase
import de.schoolagent.domain.model.SchoolEvent
import de.schoolagent.domain.model.Source
import java.time.Instant
import java.util.UUID

/** Read-side DTOs: API shape is explicit and stable, decoupled from entities. */

data class CaseSummaryDto(
    val id: UUID,
    val childId: UUID?,
    val childName: String?,
    val childColor: String?,
    val title: String,
    val summary: String?,
    val category: String,
    val status: String,
    val importance: String,
    val score: Int,
    val investigationComplete: Boolean,
    val openedAt: Instant?,
    val closedAt: Instant?,
    val updatedAt: Instant?,
    val openActions: Long,
    val informationOnly: Boolean = false,
    val classificationOverridden: Boolean = false,
    val classificationReview: Boolean = false,
    val read: Boolean = false,
    val revision: String = "",
    val canUndo: Boolean = false,
    val sourceTypes: List<String> = emptyList(),
    val latestChange: String? = null,
    val sourceDate: Instant? = null,
    val sourceDateLabel: String? = null,
    val inboxCopy: de.schoolagent.inbox.InboxCopy? = null,
    val relevance: de.schoolagent.inbox.CaseRelevance? = null,
)

data class SourceDto(
    val id: UUID,
    val sourceType: String,
    val externalId: String,
    val title: String?,
    val sender: String?,
    val receivedAt: Instant?,
    val canonicalUrl: String?,
    val mimeType: String?,
    val fetchStatus: String,
    val forwardedBy: String? = null,
    val originalSentAt: Instant? = null,
)

data class EvidenceDto(
    val id: UUID,
    val sourceId: UUID,
    val kind: String,
    val subject: String,
    val confidence: Double?,
)

data class ParentActionDto(
    val id: UUID,
    val title: String,
    val description: String?,
    val dueAt: Instant?,
    val assignedTo: UUID?,
    val assignedToName: String? = null,
    val status: String,
    val externalUrl: String?,
    val required: Boolean = true,
    val createdAt: Instant? = null,
    val acknowledgedBy: List<AcknowledgmentDto> = emptyList(),
    val externalLink: de.schoolagent.actions.ActionLink? = null,
)

data class ChildRequirementDto(
    val id: UUID,
    val description: String,
    val relevantOn: String?,
    val status: String,
    val required: Boolean = true,
)

data class SchoolEventDto(
    val id: UUID,
    val title: String,
    val eventType: String,
    val startTime: Instant,
    val endTime: Instant?,
    val allDay: Boolean,
    val location: String?,
    val status: String,
)

data class ExternalFormDto(
    val id: UUID,
    val url: String,
    val deadline: Instant?,
    val requiresConsent: Boolean,
    val requiresPayment: Boolean,
    val accessStatus: String,
    val externalLink: de.schoolagent.actions.ActionLink? = null,
)

data class CaseChangeDto(
    val id: UUID,
    val changeType: String,
    val targetType: String,
    val targetId: UUID?,
    val createdAt: Instant,
    val before: String? = null,
    val after: String? = null,
)

data class StatusEntryDto(
    val id: UUID,
    val fromStatus: String?,
    val toStatus: String,
    val reason: String?,
    val actor: String,
    val createdAt: Instant,
)

data class InvestigationDto(
    val id: UUID,
    val status: String,
    val startedAt: Instant,
    val finishedAt: Instant?,
    val pagesVisited: Int,
    val aiCalls: Int,
    val steps: List<StepDto>,
)

data class StepDto(
    val id: UUID,
    val stepType: String,
    val target: String,
    val outcome: String,
    val createdAt: Instant,
)

data class CaseDetailDto(
    val id: UUID,
    val childId: UUID?,
    val childName: String?,
    val childColor: String?,
    val title: String,
    val summary: String?,
    val category: String,
    val status: String,
    val importance: String,
    val score: Int,
    val confidence: Double?,
    val investigationComplete: Boolean,
    val investigationNotes: String?,
    val openedAt: Instant?,
    val closedAt: Instant?,
    val muted: Boolean = false,
    val sources: List<SourceDto>,
    val evidence: List<EvidenceDto>,
    val actions: List<ParentActionDto>,
    val requirements: List<ChildRequirementDto>,
    val events: List<SchoolEventDto>,
    val externalForms: List<ExternalFormDto>,
    val changes: List<CaseChangeDto>,
    val statusHistory: List<StatusEntryDto>,
    val investigations: List<InvestigationDto>,
    val informationOnly: Boolean = false,
    val classificationOverridden: Boolean = false,
    val classificationReview: Boolean = false,
    val read: Boolean = false,
    val revision: String = "",
    val canUndo: Boolean = false,
    val relevance: de.schoolagent.inbox.CaseRelevance? = null,
)

fun SchoolCase.toSummary(
    openActions: Long,
    childId: java.util.UUID? = null,
    childName: String? = null,
    childColor: String? = null,
) = CaseSummaryDto(
    id = id,
    childId = childId,
    childName = childName,
    childColor = childColor,
    title = title,
    summary = summary,
    category = category.name,
    status = status.name,
    importance = importance.name,
    score = importanceScore,
    investigationComplete = investigationComplete,
    openedAt = openedAt.takeIf { it != Instant.EPOCH },
    closedAt = closedAt,
    updatedAt = updatedAt.takeIf { it != Instant.EPOCH },
    openActions = if (isInfo()) 0 else openActions,
    informationOnly = isInfo(),
    classificationOverridden = infoOverride != null,
    classificationReview = classificationReview,
    canUndo = status == de.schoolagent.domain.enums.CaseStatus.COMPLETED && completionSnapshot != null,
)

fun Source.toDto() = SourceDto(
    id = id,
    sourceType = sourceType.name,
    externalId = externalId,
    title = title,
    sender = sender,
    receivedAt = receivedAt,
    canonicalUrl = canonicalUrl,
    mimeType = mimeType,
    fetchStatus = fetchStatus.name,
    forwardedBy = forwardedBy,
    originalSentAt = originalSentAt,
)

fun Evidence.toDto() = EvidenceDto(
    id = id,
    sourceId = sourceId,
    kind = kind.name,
    subject = subject,
    confidence = confidence?.toDouble(),
)

fun ParentAction.toDto(
    assignedToName: String? = null,
    acknowledgedBy: List<AcknowledgmentDto> = emptyList(),
    externalLink: de.schoolagent.actions.ActionLink? = null,
) = ParentActionDto(
    id = id,
    title = title,
    description = description,
    dueAt = dueAt,
    assignedTo = assignedTo,
    assignedToName = assignedToName,
    status = status.name,
    externalUrl = externalUrl,
    required = required,
    createdAt = createdAt.takeIf { it != Instant.EPOCH },
    acknowledgedBy = acknowledgedBy,
    externalLink = externalLink,
)

fun ChildRequirement.toDto() = ChildRequirementDto(
    id = id,
    description = description,
    relevantOn = relevantOn?.toString(),
    status = status.name,
    required = required,
)

fun SchoolEvent.toDto() = SchoolEventDto(
    id = id,
    title = title,
    eventType = eventType.name,
    startTime = startTime,
    endTime = endTime,
    allDay = allDay,
    location = location,
    status = status.name,
)

fun ExternalForm.toDto() = ExternalFormDto(
    id = id,
    url = url,
    deadline = deadline,
    requiresConsent = requiresConsent,
    requiresPayment = requiresPayment,
    accessStatus = accessStatus.name,
    externalLink = de.schoolagent.actions.ActionUrls.http(pageUrl)?.let { de.schoolagent.actions.ActionLink(it, "PAGE") },
)

fun CaseChange.toDto() = CaseChangeDto(
    id = id,
    changeType = changeType.name,
    targetType = targetType.name,
    targetId = targetId,
    createdAt = createdAt,
    before = beforeJson,
    after = afterJson,
)

fun CaseStatusEntry.toDto() = StatusEntryDto(
    id = id,
    fromStatus = fromStatus?.name,
    toStatus = toStatus.name,
    reason = reason,
    actor = actor.name,
    createdAt = createdAt,
)

fun InvestigationStep.toDto() = StepDto(
    id = id,
    stepType = stepType.name,
    target = target,
    outcome = outcome.name,
    createdAt = createdAt,
)

fun Investigation.toDto(steps: List<InvestigationStep>) = InvestigationDto(
    id = id,
    status = status.name,
    startedAt = startedAt,
    finishedAt = finishedAt,
    pagesVisited = pagesVisited,
    aiCalls = aiCalls,
    steps = steps.map { it.toDto() },
)
