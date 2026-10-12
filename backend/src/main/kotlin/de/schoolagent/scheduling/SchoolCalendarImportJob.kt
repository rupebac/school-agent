package de.schoolagent.scheduling

import de.schoolagent.calendar.SchoolCalendarService
import de.schoolagent.domain.repo.SchoolCalendarEntryRepository
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Year
import java.time.ZoneId

/**
 * Keeps Bavarian holidays/vacations in the internal calendar without a
 * Settings click: fill an empty table, then refresh on 1 January.
 */
@Component
@ConditionalOnProperty(prefix = "schoolagent.calendar", name = ["auto-import"], havingValue = "true", matchIfMissing = true)
class SchoolCalendarImportJob(
    private val calendar: SchoolCalendarService,
    private val repo: SchoolCalendarEntryRepository,
) {

    private val log = LoggerFactory.getLogger(javaClass)
    private val berlin = ZoneId.of("Europe/Berlin")

    @Scheduled(cron = "0 */30 * * * *", zone = "Europe/Berlin")
    fun fillIfEmpty() {
        if (repo.count() > 0) return
        importCurrentAndNext("empty table")
    }

    @Scheduled(cron = "0 20 0 1 1 *", zone = "Europe/Berlin")
    fun newYear() {
        importCurrentAndNext("1 January")
    }

    private fun importCurrentAndNext(reason: String) {
        val year = Year.now(berlin).value
        for (y in listOf(year, year + 1)) {
            runCatching { calendar.importBavarian(y) }
                .onSuccess { log.info("school calendar import {} ({}): +{} new, {} already there", y, reason, it.imported, it.alreadyPresent) }
                .onFailure { log.warn("school calendar import {} failed: {}", y, it.message) }
        }
    }
}
