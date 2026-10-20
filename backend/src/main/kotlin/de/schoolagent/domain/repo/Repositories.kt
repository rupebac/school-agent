package de.schoolagent.domain.repo

import de.schoolagent.domain.enums.ActionStatus
import de.schoolagent.domain.enums.CaseStatus
import de.schoolagent.domain.enums.ConnectorType
import de.schoolagent.domain.enums.ModelRole
import de.schoolagent.domain.model.AppUser
import de.schoolagent.domain.model.AuditEntry
import de.schoolagent.domain.model.AiUsage
import de.schoolagent.domain.model.CaseChange
import de.schoolagent.domain.model.CaseStatusEntry
import de.schoolagent.domain.model.Child
import de.schoolagent.domain.model.ChildRequirement
import de.schoolagent.domain.model.ConnectorHealth
import de.schoolagent.domain.model.Evidence
import de.schoolagent.domain.model.ExternalForm
import de.schoolagent.domain.model.FamilyRule
import de.schoolagent.domain.model.Investigation
import de.schoolagent.domain.model.InvestigationStep
import de.schoolagent.domain.model.ModelConfig
import de.schoolagent.domain.model.Notification
import de.schoolagent.domain.model.ParentAction
import de.schoolagent.domain.model.SchoolCase
import de.schoolagent.domain.model.SchoolEvent
import de.schoolagent.domain.model.SecretRef
import de.schoolagent.domain.model.Setting
import de.schoolagent.domain.model.Source
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface AppUserRepository : JpaRepository<AppUser, UUID> {
    fun findByName(name: String): AppUser?
}

interface ChildRepository : JpaRepository<Child, UUID> {
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select c from Child c where c.id = :id")
    fun lockById(@org.springframework.data.repository.query.Param("id") id: UUID): Child?
    fun findByName(name: String): Child?
}

interface SchoolCaseRepository : JpaRepository<SchoolCase, UUID> {
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select c from SchoolCase c where c.id = :id")
    fun lockById(@org.springframework.data.repository.query.Param("id") id: UUID): SchoolCase?
    fun findByDedupKey(dedupKey: String): SchoolCase?
    fun findByStatusIn(statuses: Collection<CaseStatus>): List<SchoolCase>
    fun findByStatusOrderByOpenedAtDesc(status: CaseStatus): List<SchoolCase>
}

interface CaseReadRepository : JpaRepository<de.schoolagent.domain.model.CaseRead, de.schoolagent.domain.model.CaseReadId> {
    fun findByUserId(userId: UUID): List<de.schoolagent.domain.model.CaseRead>
}

interface StoredCaseSummaryRepository : JpaRepository<de.schoolagent.domain.model.StoredCaseSummary, de.schoolagent.domain.model.CaseSummaryId>

interface CaseRelevanceRepository : JpaRepository<de.schoolagent.domain.model.StoredCaseRelevance, UUID>

interface CaseStatusEntryRepository : JpaRepository<CaseStatusEntry, UUID> {
    fun findByCaseIdOrderByCreatedAtAsc(caseId: UUID): List<CaseStatusEntry>
}

interface CaseChangeRepository : JpaRepository<CaseChange, UUID> {
    fun findByCaseIdOrderByCreatedAtAsc(caseId: UUID): List<CaseChange>
}

interface SourceRepository : JpaRepository<Source, UUID> {
    fun findByCaseIdIn(caseIds: Collection<UUID>): List<Source>
    fun findByDedupKey(dedupKey: String): Source?
    fun existsByDedupKey(dedupKey: String): Boolean
    fun findByCaseId(caseId: UUID): List<Source>
}

interface EvidenceRepository : JpaRepository<Evidence, UUID> {
    fun existsByDedupKey(dedupKey: String): Boolean
    fun findByCaseId(caseId: UUID): List<Evidence>
}

interface InvestigationRepository : JpaRepository<Investigation, UUID> {
    fun findByCaseIdOrderByStartedAtDesc(caseId: UUID): List<Investigation>
}

interface InvestigationStepRepository : JpaRepository<InvestigationStep, UUID> {
    fun findByInvestigationIdOrderByCreatedAtAsc(investigationId: UUID): List<InvestigationStep>
}

interface ParentActionRepository : JpaRepository<ParentAction, UUID> {
    fun findByCaseIdIn(caseIds: Collection<UUID>): List<ParentAction>
    fun findByDedupKey(dedupKey: String): ParentAction?
    fun existsByDedupKey(dedupKey: String): Boolean
    fun findByCaseId(caseId: UUID): List<ParentAction>
    fun findByStatusIn(statuses: Collection<ActionStatus>): List<ParentAction>
    fun findByCaseIdAndStatusIn(caseId: UUID, statuses: Collection<ActionStatus>): List<ParentAction>
}

interface ChildRequirementRepository : JpaRepository<ChildRequirement, UUID> {
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select r from ChildRequirement r where r.id = :id")
    fun lockById(id: UUID): ChildRequirement?
    fun findByCaseIdIn(caseIds: Collection<UUID>): List<ChildRequirement>
    fun findByDedupKey(dedupKey: String): ChildRequirement?
    fun existsByDedupKey(dedupKey: String): Boolean
    fun findByCaseId(caseId: UUID): List<ChildRequirement>
}

interface SchoolEventRepository : JpaRepository<SchoolEvent, UUID> {
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select e from SchoolEvent e where e.id = :id")
    fun lockForCalendarProjection(id: UUID): java.util.Optional<SchoolEvent>

    @org.springframework.data.jpa.repository.Query("""
        select e.id from SchoolEvent e, SchoolCase c
        where c.id = e.caseId and c.childId = :childId
          and ((coalesce(e.endTime, e.startTime) >= :from
            and e.status <> de.schoolagent.domain.enums.EventStatus.CANCELLED
            and e.eventType <> de.schoolagent.domain.enums.EventType.SCHOOL_REPORT)
            or (e.googleEventId is not null and
              (e.status = de.schoolagent.domain.enums.EventStatus.CANCELLED
                or e.eventType = de.schoolagent.domain.enums.EventType.SCHOOL_REPORT)))
          and (e.googleSyncStatus = de.schoolagent.domain.enums.GoogleSyncStatus.PENDING
            or (e.googleSyncStatus = de.schoolagent.domain.enums.GoogleSyncStatus.ERROR and e.updatedAt <= :retryBefore))
        order by e.startTime, e.id
    """)
    fun findPendingCalendarProjections(childId: UUID, from: java.time.Instant, retryBefore: java.time.Instant,
        pageable: org.springframework.data.domain.Pageable): List<UUID>

    @org.springframework.data.jpa.repository.Modifying(clearAutomatically = true, flushAutomatically = true)
    @org.springframework.data.jpa.repository.Query("""
        update SchoolEvent e set e.googleSyncStatus = de.schoolagent.domain.enums.GoogleSyncStatus.PENDING,
          e.updatedAt = :now
        where e.caseId in (select c.id from SchoolCase c where c.childId = :childId or (:childId is null and c.childId is null))
          and e.googleCalendarId is not null and e.googleCalendarId <> :calendarId
    """)
    fun queueCalendarReconciliation(childId: UUID?, calendarId: String, now: java.time.Instant): Int

    fun findByCaseIdIn(caseIds: Collection<UUID>): List<SchoolEvent>
    fun findByDedupKey(dedupKey: String): SchoolEvent?
    fun existsByDedupKey(dedupKey: String): Boolean
    fun findByCaseId(caseId: UUID): List<SchoolEvent>
    fun findByGoogleCalendarIdAndGoogleEventId(googleCalendarId: String, googleEventId: String): SchoolEvent?
    fun findByStartTimeBetweenOrderByStartTimeAsc(from: java.time.Instant, to: java.time.Instant): List<SchoolEvent>
    fun findByStartTimeGreaterThanEqualOrderByStartTimeAsc(from: java.time.Instant): List<SchoolEvent>
}

interface ExternalFormRepository : JpaRepository<ExternalForm, UUID> {
    fun findByCaseId(caseId: UUID): List<ExternalForm>
    fun findByUrlAndCaseId(url: String, caseId: UUID): ExternalForm?
}

interface NotificationRepository : JpaRepository<Notification, UUID> {
    fun existsByDedupKeyStartingWith(prefix: String): Boolean
    fun findTop20ByChannelOrderByCreatedAtDesc(channel: de.schoolagent.domain.enums.NotificationChannel): List<Notification>
    @org.springframework.data.jpa.repository.Query("select n from Notification n where n.templatePayload is not null and n.nextAttemptAt <= :now order by n.createdAt")
    fun pending(now: java.time.Instant, pageable: org.springframework.data.domain.Pageable): List<Notification>
    fun findByMessageId(messageId: String): Notification?
    fun findByMessageIdIsNotNullAndStatusIn(statuses: List<de.schoolagent.domain.enums.NotificationStatus>): List<Notification>
    fun existsByDedupKey(dedupKey: String): Boolean
    fun findByDedupKey(dedupKey: String): Notification?
    fun findByCaseId(caseId: UUID): List<Notification>
    fun findFirstByRecipientIdAndCaseIdIsNotNullOrderByCreatedAtDesc(recipientId: UUID): Notification?
}

interface AuditEntryRepository : JpaRepository<AuditEntry, UUID> {
    fun findByCaseIdOrderByCreatedAtAsc(caseId: UUID): List<AuditEntry>
    fun findByCaseIdOrderByCreatedAtDesc(caseId: UUID): List<AuditEntry>
    fun findTop200ByOrderByCreatedAtDesc(): List<AuditEntry>
    @org.springframework.data.jpa.repository.Query("select a from AuditEntry a where substring(a.action, 1, 11) <> 'DIAGNOSTIC_' order by a.createdAt desc")
    fun findRecentHistory(pageable: org.springframework.data.domain.Pageable): List<AuditEntry>
    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.data.jpa.repository.Query("delete from AuditEntry a where a.action in ('DIAGNOSTIC_SCHOOL_SYNC', 'DIAGNOSTIC_SCHOOL_FEED', 'DIAGNOSTIC_SCHOOL_SNAPSHOT', 'DIAGNOSTIC_SCHOOL_RECORD', 'DIAGNOSTIC_NOTICE', 'DIAGNOSTIC_BRIEFING', 'DIAGNOSTIC_PLAN_EXAM', 'DIAGNOSTIC_WHATSAPP', 'DIAGNOSTIC_CALENDAR') and a.createdAt < :before")
    fun deleteDiagnosticsBefore(@org.springframework.data.repository.query.Param("before") before: java.time.Instant): Int
}

interface ConnectorHealthRepository : JpaRepository<ConnectorHealth, ConnectorType>

interface ModelConfigRepository : JpaRepository<ModelConfig, UUID> {
    fun findByRole(role: ModelRole): ModelConfig?
}

interface AiUsageRepository : JpaRepository<AiUsage, UUID> {
    fun findByCaseId(caseId: UUID): List<AiUsage>
}

interface FamilyRuleRepository : JpaRepository<FamilyRule, UUID> {
    fun findByRuleKey(ruleKey: String): FamilyRule?
    fun findByEnabledTrue(): List<FamilyRule>
}

interface SettingRepository : JpaRepository<Setting, String>

interface SecretRefRepository : JpaRepository<SecretRef, String>

interface SourceTranslationRepository : JpaRepository<de.schoolagent.domain.model.SourceTranslation, de.schoolagent.domain.model.SourceTranslationId> {
    fun findBySourceIdAndLanguageAndContentHash(
        sourceId: java.util.UUID,
        language: String,
        contentHash: String,
    ): de.schoolagent.domain.model.SourceTranslation?
}

interface SchoolCalendarEntryRepository : JpaRepository<de.schoolagent.domain.model.SchoolCalendarEntry, UUID> {
    fun findByStartDateLessThanEqualAndEndDateGreaterThanEqual(
        startDate: java.time.LocalDate,
        endDate: java.time.LocalDate,
    ): List<de.schoolagent.domain.model.SchoolCalendarEntry>

    fun findByNameAndStartDateAndEndDate(
        name: String,
        startDate: java.time.LocalDate,
        endDate: java.time.LocalDate,
    ): de.schoolagent.domain.model.SchoolCalendarEntry?

    fun findByStartDateBetweenOrderByStartDate(
        start: java.time.LocalDate,
        end: java.time.LocalDate,
    ): List<de.schoolagent.domain.model.SchoolCalendarEntry>
}

interface ActionAcknowledgmentRepository : JpaRepository<de.schoolagent.domain.model.ActionAcknowledgment, de.schoolagent.domain.model.ActionAcknowledgmentId> {
    fun findByActionId(actionId: UUID): List<de.schoolagent.domain.model.ActionAcknowledgment>
    fun existsByActionIdAndUserId(actionId: UUID, userId: UUID): Boolean
    fun deleteByActionId(actionId: UUID)
}

interface InboxCopyRepository : JpaRepository<de.schoolagent.domain.model.StoredInboxCopy, UUID>

interface WhatsappReceiptRepository : JpaRepository<de.schoolagent.domain.model.WhatsappReceipt, String>

interface SchoolRecordRepository : JpaRepository<de.schoolagent.domain.model.StoredSchoolRecord, String> {
    fun findTop100ByNotificationPendingTrueOrProjectionPendingTrueOrderByUpdatedAtAsc(): List<de.schoolagent.domain.model.StoredSchoolRecord>
    fun findByChildIdAndKindAndActiveTrue(childId: UUID, kind: de.schoolagent.connectors.school.SchoolRecordKind): List<de.schoolagent.domain.model.StoredSchoolRecord>
    fun findByNamespaceAndKindAndActiveTrue(namespace: String, kind: de.schoolagent.connectors.school.SchoolRecordKind): List<de.schoolagent.domain.model.StoredSchoolRecord>
}
