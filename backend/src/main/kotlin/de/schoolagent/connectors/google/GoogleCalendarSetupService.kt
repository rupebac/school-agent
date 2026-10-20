package de.schoolagent.connectors.google

import de.schoolagent.connectors.calendar.CalendarAdminService
import de.schoolagent.connectors.calendar.CalendarApiException
import de.schoolagent.connectors.calendar.CalendarInfoDto
import de.schoolagent.domain.model.Setting
import de.schoolagent.domain.repo.ChildRepository
import de.schoolagent.domain.repo.SettingRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock

/**
 * The "Jamie – Schule" calendar lifecycle (spec §4): auto-detect whether it
 * exists, offer to create + share it with both parents, and remember the
 * chosen calendar id in the settings table (overrides the env default).
 */
@Service
class GoogleCalendarSetupService(
    private val admin: CalendarAdminService,
    private val oauth: GoogleOAuthService,
    private val props: GoogleProperties,
    private val settings: SettingRepository,
    private val kids: ChildRepository,
    private val clock: Clock,
    private val events: de.schoolagent.domain.repo.SchoolEventRepository? = null,
) {

    data class SetupStatus(
        val connected: Boolean,
        val configuredCalendarId: String,
        val configuredIsPrimary: Boolean,
        val expectedName: String,
        val schoolCalendarFound: CalendarInfoDto?,
        val calendars: List<CalendarInfoDto>,
    )

    /** Effective calendar id for a kid: stored choice wins over the env property. */
    fun resolveCalendarId(childId: java.util.UUID? = null): String =
        if (settings.findById(readyKey(childId)).orElse(null)?.valueJson == "false") "primary" else
        settings.findById(childId?.let { settingKey(it) } ?: SETTING_KEY).orElse(null)
            ?.valueJson?.trim()?.takeIf { it.isNotBlank() } ?: if (childId == null) props.calendarId else "primary"

    fun status(childId: java.util.UUID? = null): SetupStatus {
        if (!oauth.isConfigured(childId)) {
            return SetupStatus(
                connected = false,
                configuredCalendarId = resolveCalendarId(childId),
                configuredIsPrimary = resolveCalendarId(childId) == "primary",
                expectedName = expectedName(childId),
                schoolCalendarFound = null,
                calendars = emptyList(),
            )
        }
        val calendars = scoped(childId) { admin.listCalendars() }
        return SetupStatus(
            connected = true,
            configuredCalendarId = resolveCalendarId(childId),
            configuredIsPrimary = resolveCalendarId(childId) == "primary",
            expectedName = expectedName(childId),
            schoolCalendarFound = calendars.firstOrNull { it.summary == expectedName(childId) },
            calendars = calendars,
        )
    }

    /**
     * Creates the school calendar if missing (adopts the existing one by name —
     * never duplicates), shares it with [shareWith] parents, persists the id.
     */
    @Transactional
    fun ensureSchoolCalendar(childId: java.util.UUID?, shareWith: List<String>): CalendarInfoDto = scoped(childId) {
        if (!oauth.isConfigured(childId)) {
            throw GoogleNotConnectedException("connect Google first")
        }
        val name = expectedName(childId)
        val existing = admin.listCalendars().firstOrNull { it.summary == name }
        val calendar = existing
            ?: try {
                admin.createCalendar(name, "Europe/Berlin")
            } catch (e: CalendarApiException) {
                throw IllegalStateException("Kalender konnte nicht erstellt werden: ${e.message}", e)
            }
        for (email in shareWith.map { it.trim() }.filter { it.contains('@') }) {
            admin.shareWithUser(calendar.id, email)
        }
        val key = childId?.let { settingKey(it) } ?: SETTING_KEY
        settings.save(Setting(key = key, valueJson = calendar.id, updatedAt = clock.instant()))
        events?.queueCalendarReconciliation(childId, calendar.id, clock.instant())
        settings.save(Setting(key = readyKey(childId), valueJson = "true", updatedAt = clock.instant()))
        calendar
    }

    private fun <T> scoped(childId: java.util.UUID?, block: () -> T): T {
        val rest = admin as? de.schoolagent.connectors.calendar.CalendarAdminRestService
        rest?.withChild(childId)
        return try { block() } finally { rest?.withChild(null) }
    }

    fun forgetConfiguredCalendar() {
        settings.deleteById(SETTING_KEY)
    }

    private fun expectedName(childId: java.util.UUID?): String =
        childId?.let { kids.findById(it).orElse(null)?.name }?.let { "$it – Schule" }
            ?: props.schoolCalendarName

    companion object {
        fun readyKey(childId: java.util.UUID?) = childId?.let { "google.$it.calendar-ready" } ?: "google.calendar-ready"
        const val SETTING_KEY = "google.calendar-id"
        fun settingKey(childId: java.util.UUID) = "google.$childId.calendar-id"
    }
}

class GoogleNotConnectedException(message: String) : RuntimeException(message)
