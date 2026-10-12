package de.schoolagent.connectors.school

import de.schoolagent.connectors.elternportal.ElternportalConfigService
import de.schoolagent.connectors.schulmanager.SchulmanagerSession
import de.schoolagent.domain.model.Setting
import de.schoolagent.domain.repo.ChildRepository
import de.schoolagent.domain.repo.SettingRepository
import de.schoolagent.security.SecretVault
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import java.util.UUID

@Service
class SchoolConnectionService(
    private val settings: SettingRepository,
    private val vault: SecretVault,
    private val kids: ChildRepository,
    private val portal: ElternportalConfigService,
    private val clock: Clock,
) {
    data class Status(
        val provider: SchoolProvider?, val configured: Boolean, val url: String?, val username: String?,
        val password: String?, val hasPassword: Boolean, val studentId: String?, val institutionId: String?,
        val studentName: String?, val lastSync: Instant?,
        val sections: List<SectionStatus> = emptyList(),
    )
    data class SectionStatus(val kind: SchoolRecordKind, val label: String, val status: SchoolFeedStatus,
                             val records: Int, val message: String?, val checkedAt: String, val lastSuccessAt: String? = null)
    private val json = com.fasterxml.jackson.databind.ObjectMapper()
    fun recordSections(childId: UUID, feeds: List<SchoolRecordFeed>) {
        val previous = sections(childId).associateBy { it.kind }
        if (feeds.isNotEmpty()) put(childId, "sections", json.writeValueAsString(feeds.map {
            SectionStatus(it.kind, it.kind.label, it.status, it.records.size, it.message, clock.instant().toString(),
                if (it.status == SchoolFeedStatus.AVAILABLE) clock.instant().toString() else previous[it.kind]?.lastSuccessAt)
        }))
    }
    private fun sections(childId: UUID): List<SectionStatus> = value(childId, "sections")?.let { raw ->
        runCatching { json.readTree(raw).map { SectionStatus(SchoolRecordKind.valueOf(it.path("kind").asText()), it.path("label").asText(),
            SchoolFeedStatus.valueOf(it.path("status").asText()), it.path("records").asInt(), it.path("message").takeIf { m -> m.isTextual }?.asText(), it.path("checkedAt").asText(), it.path("lastSuccessAt").takeIf { m -> m.isTextual }?.asText()) } }.getOrDefault(emptyList())
    } ?: emptyList()
    data class SaveRequest(
        val provider: SchoolProvider, val username: String, val password: String? = null, val url: String? = null,
        val studentId: String? = null, val institutionId: String? = null, val studentName: String? = null,
    )

    fun provider(childId: UUID): SchoolProvider? = value(childId, "provider")?.let(SchoolProvider::valueOf)
        ?: if (portal.config(childId) != null) SchoolProvider.ELTERNPORTAL else null

    fun credentials(childId: UUID): SchoolCredentials? = when (val provider = provider(childId)) {
        SchoolProvider.ELTERNPORTAL -> portal.config(childId)?.let {
            it.password?.let { password -> SchoolCredentials(provider, it.url, it.username, password, value(childId, "student-id")) }
        }
        SchoolProvider.SCHULMANAGER -> {
            val username = vault.read("schulmanager.$childId.username")
            val password = vault.read("schulmanager.$childId.password")
            if (username == null || password == null) null else SchoolCredentials(provider, SchulmanagerSession.BASE_URL,
                username, password, value(childId, "student-id"), value(childId, "institution-id"))
        }
        null -> null
    }

    fun status(childId: UUID): Status {
        val provider = provider(childId)
        val credentials = credentials(childId)
        val old = if (provider == SchoolProvider.ELTERNPORTAL) portal.config(childId) else null
        return Status(provider, credentials != null, credentials?.url ?: old?.url, credentials?.username ?: old?.username,
            old?.password, credentials != null, credentials?.studentId, credentials?.institutionId,
            value(childId, "student-name"), value(childId, "last-sync")?.let(Instant::parse)
                ?: if (provider == SchoolProvider.ELTERNPORTAL) portal.lastSync(childId) else null, sections(childId))
    }

    /** One active school connection per child, including callers using the legacy Elternportal API. */
    @Transactional
    fun save(childId: UUID, request: SaveRequest): Status {
        kids.lockById(childId) ?: throw IllegalArgumentException("Child not found")
        val current = credentials(childId)
        require(request.username.isNotBlank()) { "Username is required" }
        val password = request.password?.takeIf { it.isNotBlank() }
            ?: current?.takeIf { it.provider == request.provider && it.username == request.username.trim() }?.password
            ?: throw IllegalArgumentException("Enter a password for the new connection")
        if (request.provider == SchoolProvider.SCHULMANAGER) {
            require(request.studentId?.toLongOrNull() != null && request.institutionId?.toLongOrNull() != null) { "Find and select the matching Schulmanager child first" }
            vault.store("schulmanager.$childId.username", request.username.trim())
            vault.store("schulmanager.$childId.password", password)
            put(childId, "student-id", request.studentId!!)
            put(childId, "institution-id", request.institutionId!!)
            put(childId, "student-name", request.studentName.orEmpty())
            portal.clear(childId)
        } else {
            portal.save(childId, request.url.orEmpty(), request.username, password)
            clearSchulmanager(childId, keepStudent = current?.provider == SchoolProvider.ELTERNPORTAL &&
                current.username == request.username.trim() && current.url.trimEnd('/') == request.url.orEmpty().trimEnd('/'))
        }
        if (current?.provider != request.provider || current.username != request.username.trim() ||
            (request.provider == SchoolProvider.SCHULMANAGER && current.studentId != request.studentId) ||
            (request.provider == SchoolProvider.ELTERNPORTAL && current.url.trimEnd('/') != request.url.orEmpty().trimEnd('/'))) {
            settings.deleteById(key(childId, "last-sync"))
            settings.deleteById("elternportal.$childId.last-sync")
        }
        put(childId, "provider", request.provider.name)
        return status(childId)
    }

    @Transactional
    fun clear(childId: UUID) {
        kids.lockById(childId) ?: throw IllegalArgumentException("Child not found")
        portal.clear(childId)
        clearSchulmanager(childId)
        settings.deleteById(key(childId, "provider"))
        settings.deleteById(key(childId, "last-sync"))
    }

    fun configuredKids() = kids.findAll().filter { credentials(it.id) != null }
    fun recordSync(childId: UUID) = put(childId, "last-sync", clock.instant().toString())
    fun bindStudent(childId: UUID, student: SchoolStudent) {
        put(childId, "student-id", student.id)
        put(childId, "student-name", student.name)
    }
    fun hasRecordBaseline(childId: UUID, namespace: String, kind: SchoolRecordKind) = value(childId, "records.$namespace.$kind") != null
    fun recordBaseline(childId: UUID, namespace: String, kind: SchoolRecordKind) = put(childId, "records.$namespace.$kind", clock.instant().toString())

    /** Public navigation metadata; reading links must not decrypt account credentials. */
    data class Navigation(val provider: SchoolProvider, val url: String)
    fun navigation(childId: UUID): Navigation? {
        val provider = value(childId, "provider")?.let(SchoolProvider::valueOf)
        if (provider == SchoolProvider.SCHULMANAGER) return Navigation(provider, SchulmanagerSession.BASE_URL)
        return portal.url(childId)?.let { Navigation(SchoolProvider.ELTERNPORTAL, it) }
    }
    private fun clearSchulmanager(childId: UUID, keepStudent: Boolean = false) {
        vault.delete("schulmanager.$childId.username")
        vault.delete("schulmanager.$childId.password")
        (if (keepStudent) listOf("institution-id") else listOf("student-id", "institution-id", "student-name", "sections")).forEach { settings.deleteById(key(childId, it)) }
    }
    private fun key(childId: UUID, field: String) = "school.$childId.$field"
    private fun value(childId: UUID, field: String) = settings.findById(key(childId, field)).orElse(null)?.valueJson
    private fun put(childId: UUID, field: String, value: String) {
        settings.save(Setting(key = key(childId, field), valueJson = value, updatedAt = clock.instant()))
    }
}
