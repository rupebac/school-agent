package de.schoolagent.scheduling

import de.schoolagent.briefing.BriefingService
import de.schoolagent.domain.repo.AppUserRepository
import de.schoolagent.domain.repo.NotificationRepository
import de.schoolagent.security.UserDetailsAdapter
import de.schoolagent.whatsapp.WaTemplates
import de.schoolagent.whatsapp.WhatsAppConfigService
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.DayOfWeek
import java.time.Clock
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Daily + weekly briefing scheduler (spec §26): each user gets a WhatsApp
 * message at their configured time — tomorrow's plan every evening, the
 * week ahead on Sundays. Deduped per user+day/week.
 */
@Component
class BriefingJob(
    private val users: AppUserRepository,
    private val briefing: BriefingService,
    private val notifications: NotificationRepository,
    private val whatsappConfig: WhatsAppConfigService,
    private val outbox: de.schoolagent.whatsapp.WhatsappOutbox,
    private val clock: Clock,
) {

    private val log = LoggerFactory.getLogger(javaClass)
    private val berlin = ZoneId.of("Europe/Berlin")
    private val dateFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd")

    @Scheduled(cron = "\${schoolagent.briefing.check-cron:0 */5 * * * *}")
    @org.springframework.transaction.annotation.Transactional
    fun check() {
        if (!whatsappConfig.isConfigured()) return
        val now = java.time.ZonedDateTime.now(clock.withZone(berlin))
        if (now.hour < 4) return
        val today = now.toLocalDate()
        val currentMinutes = now.hour * 60 + now.minute

        for (account in users.findAll()) {
            if (account.whatsappNumber.isNullOrBlank()) continue

            if (account.briefingEnabled) {
                val targetMinutes = parseTime(account.briefingTime)
                if (targetMinutes != null && currentMinutes >= targetMinutes && currentMinutes < targetMinutes + 15) {
                    sendDaily(account, today)
                }
            }

            if (account.weeklyEnabled && now.dayOfWeek == DayOfWeek.SUNDAY) {
                val targetMinutes = parseTime(account.weeklyTime)
                if (targetMinutes != null && currentMinutes >= targetMinutes && currentMinutes < targetMinutes + 15) {
                    sendWeekly(account, today)
                }
            }
        }
    }

    private fun sendDaily(account: de.schoolagent.domain.model.AppUser, today: LocalDate) {
        val dedupKey = "briefing:${account.id}:${today.format(dateFmt)}"
        if (notifications.existsByDedupKey(dedupKey) || notifications.existsByDedupKeyStartingWith("$dedupKey:")) return
        enqueue(account, dedupKey, today.plusDays(1), false)
    }

    private fun sendWeekly(account: de.schoolagent.domain.model.AppUser, today: LocalDate) {
        val dedupKey = "weekly:${account.id}:${briefing.weekKey(today)}"
        if (notifications.existsByDedupKey(dedupKey) || notifications.existsByDedupKeyStartingWith("$dedupKey:")) return
        enqueue(account, dedupKey, today.plusDays(1), true)
    }

    private fun enqueue(account: de.schoolagent.domain.model.AppUser, baseKey: String, start: LocalDate, weekly: Boolean) {
        // Once the planned day/week begins, these relative headings become misleading.
        val expiresAt = start.atStartOfDay(berlin).toInstant()
        briefing.plans(UserDetailsAdapter(account), start, weekly).forEach { plan ->
            val messages = WaTemplates.messages(WaTemplates.Kind.PLAN, de.schoolagent.i18n.UserLanguage.of(account.language),
                plan.child, plan.title, "",
                plan.rows, "calendar?kid=${plan.childId}&day=$start&month=${start.toString().take(7)}", weekly = weekly)
            messages.forEachIndexed { index, message ->
                outbox.enqueue(account.id, "$baseKey:${plan.childId}:$index", message.copy(childId = plan.childId.toString()),
                    expiresAt = expiresAt)
            }
        }
    }

    private fun parseTime(time: String): Int? = try {
        val t = LocalTime.parse(time)
        t.hour * 60 + t.minute
    } catch (e: Exception) {
        log.warn("invalid briefing_time '{}'", time)
        null
    }
}
