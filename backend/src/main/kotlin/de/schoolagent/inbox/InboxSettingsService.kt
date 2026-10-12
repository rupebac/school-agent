package de.schoolagent.inbox

import de.schoolagent.domain.enums.Actor
import de.schoolagent.domain.model.AuditEntry
import de.schoolagent.domain.model.Setting
import de.schoolagent.domain.repo.AuditEntryRepository
import de.schoolagent.domain.repo.SettingRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock

data class InboxSettings(val archiveAfterWeeks: Int = 4)

/** Family-wide fallback policy. AI estimates remain keyed only by school evidence. */
@Service
class InboxSettingsService(private val settings: SettingRepository, private val audit: AuditEntryRepository,
                           private val clock: Clock) {
    fun current() = InboxSettings(settings.findById(KEY).orElse(null)?.valueJson?.toIntOrNull()
        ?.takeIf { it in 1..52 } ?: 4)

    @Transactional
    fun update(weeks: Int, actor: Actor): InboxSettings {
        require(weeks in 1..52) { "Archive period must be between 1 and 52 weeks" }
        val previous = current().archiveAfterWeeks
        settings.save(Setting(KEY, weeks.toString(), clock.instant()))
        if (previous != weeks) audit.save(AuditEntry(actor = actor, action = "INBOX_ARCHIVE_SETTINGS_CHANGED",
            detail = """{"previousWeeks":$previous,"archiveAfterWeeks":$weeks}""", createdAt = clock.instant()))
        return InboxSettings(weeks)
    }

    companion object { const val KEY = "inbox.archive-after-weeks" }
}
