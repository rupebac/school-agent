package de.schoolagent.i18n

import de.schoolagent.ai.AiUnavailableException
import de.schoolagent.ai.JsonCompletions
import de.schoolagent.ai.ModelRouter
import de.schoolagent.common.DedupKeys
import de.schoolagent.connectors.google.GoogleOAuthService
import de.schoolagent.domain.enums.ModelRole
import de.schoolagent.domain.model.ParentAction
import de.schoolagent.domain.model.SchoolCase
import de.schoolagent.domain.model.SchoolEvent
import de.schoolagent.domain.repo.ParentActionRepository
import de.schoolagent.domain.repo.SchoolCaseRepository
import de.schoolagent.domain.repo.SchoolEventRepository
import de.schoolagent.events.CalendarProjectionService
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.util.concurrent.ConcurrentHashMap

/**
 * Inbox/calendar labels are stored in English. The reasoning prompt asks for
 * that, but older rows and a slipping model still copy German letter headings.
 */
@Service
class TitleEnglishService(
    private val router: ModelRouter,
    private val eventRepo: SchoolEventRepository,
    private val actionRepo: ParentActionRepository,
    private val caseRepo: SchoolCaseRepository,
    private val calendarProjection: CalendarProjectionService,
    private val oauth: GoogleOAuthService,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val cache = ConcurrentHashMap<String, String>()

    fun english(text: String): String {
        val trimmed = text.trim()
        if (!looksGerman(trimmed)) return trimmed
        cache[trimmed]?.let { return it }
        if (!router.available(ModelRole.TEXT)) return trimmed
        return try {
            val raw = router.complete(ModelRole.TEXT, SYSTEM, "Title:\n$trimmed")
            val translated = jsonField(raw)?.trim().orEmpty()
            if (translated.isBlank()) trimmed
            else {
                cache[trimmed] = translated
                translated
            }
        } catch (e: AiUnavailableException) {
            log.debug("title translation unavailable: {}", e.javaClass.simpleName)
            trimmed
        } catch (e: Exception) {
            log.debug("title translation failed: {}", e.javaClass.simpleName)
            trimmed
        }
    }

    @Transactional
    fun rewrite(event: SchoolEvent): SchoolEvent {
        val en = english(event.title)
        if (en == event.title) return event
        event.title = en
        event.dedupKey = DedupKeys.event(event.caseId, event.eventType, en)
        event.updatedAt = clock.instant()
        val saved = eventRepo.save(event)
        if (oauth.isConfigured(saved.childId ?: caseRepo.findById(saved.caseId).orElse(null)?.childId)) runCatching { calendarProjection.syncEvent(saved) }
        return saved
    }

    @Transactional
    fun rewrite(action: ParentAction): ParentAction {
        val en = english(action.title)
        if (en == action.title) return action
        action.title = en
        action.dedupKey = DedupKeys.action(action.caseId, en)
        action.updatedAt = clock.instant()
        return actionRepo.save(action)
    }

    @Transactional
    fun rewrite(schoolCase: SchoolCase): SchoolCase {
        val en = english(schoolCase.title)
        if (en == schoolCase.title) return schoolCase
        schoolCase.title = en
        schoolCase.updatedAt = clock.instant()
        return caseRepo.save(schoolCase)
    }

    private fun jsonField(raw: String): String? = JsonCompletions.field(raw, "translated")

    companion object {
        private const val SYSTEM = """
You write a short English label for a family's school inbox.
Return JSON only: {"translated":"..."}.
4–10 words, specific (what + when if a date is in the title).
Keep German proper nouns (Schullandheim, school names, class names like 5a).
Translate common headings: Elternbrief→Parent letter, Elternabend→Parents' evening,
Herbstkonzert→Autumn concert, Anmeldung→Registration, Ausflug→Trip.
If the title is already English, return it unchanged.
"""
    }
}
