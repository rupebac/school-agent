package de.schoolagent.whatsapp

import de.schoolagent.ai.ModelRouter
import de.schoolagent.common.DateWindow
import de.schoolagent.common.DateWindowParser
import de.schoolagent.common.TitleSimilarity
import de.schoolagent.domain.enums.ActionStatus
import de.schoolagent.domain.enums.EventStatus
import de.schoolagent.domain.enums.EventType
import de.schoolagent.domain.enums.ModelRole
import de.schoolagent.domain.enums.RequirementStatus
import de.schoolagent.domain.model.AppUser
import de.schoolagent.domain.model.ChildRequirement
import de.schoolagent.domain.model.ParentAction
import de.schoolagent.domain.model.SchoolCase
import de.schoolagent.domain.repo.ChildRepository
import de.schoolagent.domain.repo.ChildRequirementRepository
import de.schoolagent.domain.repo.EvidenceRepository
import de.schoolagent.domain.repo.ParentActionRepository
import de.schoolagent.domain.repo.SchoolCalendarEntryRepository
import de.schoolagent.domain.repo.SchoolCaseRepository
import de.schoolagent.domain.repo.SchoolEventRepository
import de.schoolagent.domain.repo.SourceRepository
import de.schoolagent.i18n.UserLanguage
import de.schoolagent.i18n.WaCopy
import de.schoolagent.security.KidAccessService
import de.schoolagent.security.UserDetailsAdapter
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

data class ChatTurn(val role: String, val content: String)

data class ChatRef(val id: UUID, val title: String)

/** A paragraph/bullet and its sources stay together across channel renderers. */
data class ChatSection(val text: String, val refs: List<ChatRef> = emptyList())

data class ChatAnswer(
    val text: String,
    val refs: List<ChatRef> = emptyList(),
    val sections: List<ChatSection> = emptyList(),
) {
    fun forWhatsApp(frontendUrl: String): String {
        val base = frontendUrl.trimEnd('/')
        val linked = mutableSetOf<UUID>()
        fun links(refs: List<ChatRef>) = refs.filter { linked.add(it.id) }
            .joinToString("\n") { "↗ $base/cases/${it.id}" }
        if (sections.isNotEmpty()) return sections.joinToString("\n\n") { section ->
            listOf(section.text, links(section.refs)).filter { it.isNotBlank() }.joinToString("\n")
        }
        // Deterministic/legacy answers have no paragraph-to-source mapping. Do not
        // guess from title similarity or repeat the case titles as a second list.
        return listOf(text, links(refs)).filter { it.isNotBlank() }.joinToString("\n\n")
    }
}

/**
 * Read-only Q&A (spec §43): web chat and WhatsApp share this path.
 * Answers come from cases, events, letters/evidence, and date windows.
 * Never mutates state.
 */
@Service
class QuestionAnsweringService(
    private val caseRepo: SchoolCaseRepository,
    private val eventRepo: SchoolEventRepository,
    private val actionRepo: ParentActionRepository,
    private val evidenceRepo: EvidenceRepository,
    private val sourceRepo: SourceRepository,
    private val requirementRepo: ChildRequirementRepository,
    private val calendarRepo: SchoolCalendarEntryRepository,
    private val access: KidAccessService,
    private val router: ModelRouter,
    private val clock: Clock,
    private val childRepo: ChildRepository,
) {

    private val log = LoggerFactory.getLogger(javaClass)
    private val berlin = ZoneId.of("Europe/Berlin")
    private val dtFmt = DateTimeFormatter.ofPattern("EEE d MMM, HH:mm")
    private val dayFmt = DateTimeFormatter.ofPattern("d MMM yyyy")
    private val sessions = ConcurrentHashMap<UUID, MutableList<ChatTurn>>()

    private val stopWords = setOf(
        "when", "what", "where", "who", "how", "is", "the", "a", "an", "do", "we",
        "need", "to", "for", "next", "about", "does", "did", "has", "have", "any",
        "place", "they", "this", "that", "there", "talk", "talking", "mention",
        "mentioned", "letter", "letters", "before", "after", "between", "and",
        "from", "until", "till", "week", "month", "year", "please", "something",
        "are", "was", "were", "will", "can", "our", "your", "their", "with",
        "but", "not", "you", "all", "been", "just",
        "wann", "was", "wo", "wer", "wie", "ist", "sind", "haben", "für", "der",
        "die", "das", "nach", "vor", "und", "eine", "ein",
        "cuando", "que", "donde", "quien", "como", "despues", "antes", "entre",
        "del", "los", "las", "una", "hay", "algo", "estan", "hablando",
    )

    fun answer(
        question: String,
        user: AppUser,
        history: List<ChatTurn> = emptyList(),
        focusCaseId: UUID? = null,
        childId: UUID? = null,
    ): ChatAnswer {
        val lang = UserLanguage.of(user.language)
        val trimmed = question.trim()
        if (trimmed.isEmpty()) return ChatAnswer(WaCopy.chatInvite(lang))
        if (looksLikeSmallTalk(trimmed) && history.isEmpty()) return ChatAnswer(WaCopy.chatInvite(lang))

        val adapter = UserDetailsAdapter(user)
        if (childId != null) access.requireAccess(adapter, childId)
        val authorized = access.accessibleKidIds(adapter).filter { childId == null || it == childId }
        val children = childRepo.findAll().filter { it.id in authorized }
        val named = children.filter {
            Regex("(?<![\\p{L}\\p{N}])${Regex.escape(it.name)}(?![\\p{L}\\p{N}])", RegexOption.IGNORE_CASE)
                .containsMatchIn(trimmed)
        }.map { it.id }
        val accessible = named.ifEmpty { authorized }
        if (accessible.isEmpty()) return ChatAnswer("No kids assigned to your account.")

        val today = clock.instant().atZone(berlin).toLocalDate()
        val window = DateWindowParser.parse(trimmed, today)
        val cases = caseRepo.findAll().filter { it.childId == null || it.childId in accessible }.associateBy { it.id }
        val keywords = keywords(trimmed)
        val focus = focusCaseId?.takeIf { id ->
            val c = cases[id] ?: return@takeIf false
            visible(c.childId, id, accessible, cases)
        }
        val useFocus = focus != null && window == null && !looksLikeAgenda(trimmed)
        val detail = wantsDetail(trimmed) || useFocus

        if (chatRole() != null) {
            askModel(trimmed, history, keywords, window, accessible, cases, detail, focus, useFocus)
                ?.let { return it }
            searchDeterministic(trimmed, keywords, window, accessible, cases, focus, useFocus)
                ?.let { return it }
            fallbackList(keywords, window, accessible, cases)?.let { return it }
            return ChatAnswer("I don't have that information yet.")
        }

        return searchDeterministic(trimmed, keywords, window, accessible, cases, focus, useFocus)
            ?: fallbackList(keywords, window, accessible, cases)
            ?: ChatAnswer("I don't have that information yet.")
    }

    /** WhatsApp multi-turn: last few exchanges per parent, forgotten after idle. */
    fun answerAndRemember(question: String, user: AppUser): ChatAnswer {
        val history = sessionOf(user.id)
        val answer = answer(question, user, history)
        if (looksLikeSmallTalk(question) && history.isEmpty()) return answer
        val turns = sessions.computeIfAbsent(user.id) { mutableListOf() }
        synchronized(turns) {
            turns += ChatTurn("user", question.trim())
            turns += ChatTurn("assistant", answer.text)
            while (turns.size > 8) turns.removeAt(0)
        }
        return answer
    }

    fun looksLikeQuestion(text: String): Boolean {
        val lower = text.trim().lowercase()
        if (lower.count { it == ' ' } < 2) return false
        if (lower.endsWith("?")) return true
        val starters = listOf(
            "when", "what", "where", "who", "how", "is there", "are there",
            "does", "do we", "did", "has", "have we", "can i", "should i",
            "wann", "was", "wo", "wer", "wie", "ist", "sind", "haben",
            "cuando", "qué", "que", "dónde", "donde", "quién", "quien", "cómo", "como",
        )
        return starters.any { lower.startsWith(it) }
    }

    fun looksLikeSmallTalk(text: String): Boolean {
        val t = text.trim().lowercase().trimEnd('!', '.', '?')
        if (t in SMALL_TALK) return true
        return Regex("""^(hi|hello|hey|hola|hallo|moin)(\s+\w+){0,2}$""").matches(t)
    }

    private fun sessionOf(userId: UUID): List<ChatTurn> {
        val turns = sessions[userId] ?: return emptyList()
        synchronized(turns) { return turns.toList() }
    }

    private fun searchDeterministic(
        question: String,
        keywords: List<String>,
        window: DateWindow?,
        accessible: List<UUID>,
        cases: Map<UUID, SchoolCase>,
        focus: UUID?,
        useFocus: Boolean,
    ): ChatAnswer? {
        val q = question.lowercase()
        val mention = looksLikeMention(q)
        val matters = collectMatters(keywords, window, accessible, cases, detail = useFocus, focus = if (useFocus) focus else null)

        if (useFocus && focus != null) {
            val snap = matters.firstOrNull { it.id == focus } ?: matterSnap(focus, accessible, cases, detail = true)
            if (snap != null) return ChatAnswer(formatMatter(snap, detail = true), listOf(ChatRef(snap.id, snap.title)))
        }

        if (mention && keywords.isNotEmpty()) {
            val hits = mentionHits(keywords, window, accessible, cases)
            if (hits.isNotEmpty()) return withRefs(hits.joinToString("\n"), matters)
        }

        if (window != null && (looksLikeAgenda(q) || keywords.isEmpty())) {
            compactAgenda(window, accessible, cases)?.let { return withRefs(it, matters) }
        }

        if (looksLikeBreak(q)) {
            val breaks = upcomingBreaks(vacationsOnly = looksLikeVacationWord(q), accessible = accessible)
            if (breaks.isNotEmpty()) return ChatAnswer(breaks.joinToString("\n") { formatBreak(it) })
        }

        if (keywords.isEmpty()) return null

        val eventMatch = eventsInScope(window, accessible, cases)
            .map { event -> event to score(event.title, keywords) }
            .filter { it.second >= 0.3 }
            .maxByOrNull { it.second }

        if (!mention && eventMatch != null && eventMatch.second > 0) {
            return withRefs(formatEvent(eventMatch.first), matters)
        }

        val actionMatch = actionRepo.findAll()
            .filter { visible(null, it.caseId, accessible, cases) }
            .filter { it.status != ActionStatus.CANCELLED }
            .filter { window == null || it.dueAt == null || window.contains(it.dueAt!!, berlin) }
            .map { action -> action to score(action.title + " " + (action.description ?: ""), keywords) }
            .filter { it.second >= 0.3 }
            .maxByOrNull { it.second }

        if (!mention && actionMatch != null && actionMatch.second > 0) {
            val action = actionMatch.first
            val due = action.dueAt?.atZone(berlin)?.toLocalDate()
            val text = buildString {
                append("📋 ${action.title}")
                due?.let { append(" — due ${it.format(dayFmt)}") }
            }
            return withRefs(text, matters)
        }

        return null
    }

    private fun askModel(
        question: String,
        history: List<ChatTurn>,
        keywords: List<String>,
        window: DateWindow?,
        accessible: List<UUID>,
        cases: Map<UUID, SchoolCase>,
        detail: Boolean,
        focus: UUID?,
        useFocus: Boolean,
    ): ChatAnswer? {
        val role = chatRole() ?: return null
        return try {
            val matters = collectMatters(keywords, window, accessible, cases, detail, if (useFocus) focus else null)
            val context = mattersJson(matters, window, accessible, detail)
            val prior = history.takeLast(6).joinToString("\n") {
                val who = if (it.role.equals("assistant", true)) "Assistant" else "Parent"
                "$who: ${it.content}"
            }
            val mode = if (detail) "DETAIL" else "OVERVIEW"
            val userMsg = buildString {
                appendLine("Answer mode: $mode")
                if (useFocus && focus != null) {
                    appendLine("The parent is looking at matter id=$focus. Prefer it unless they clearly ask about something else.")
                }
                appendLine("STATE JSON:")
                appendLine(context)
                if (prior.isNotBlank()) {
                    appendLine()
                    appendLine("Earlier in this chat:")
                    appendLine(prior)
                }
                appendLine()
                append("Question: $question")
            }
            val raw = router.complete(role, ChatSkill.text, userMsg, purpose = "chat")
            val node = com.fasterxml.jackson.databind.ObjectMapper().readTree(
                raw.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim(),
            )
            val byId = matters.associate { it.id to ChatRef(it.id, it.title) }
            val sections = node.path("sections").mapNotNull { section ->
                val text = section.path("text").asText(null)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val ref = runCatching { UUID.fromString(section.path("caseId").asText()) }
                    .getOrNull()?.let(byId::get)
                ChatSection(text, listOfNotNull(ref))
            }
            if (sections.isNotEmpty()) return ChatAnswer(
                sections.joinToString("\n\n") { it.text },
                sections.flatMap { it.refs }.distinctBy { it.id },
                sections,
            )
            val text = node.path("answer").asText(null)?.takeIf { it.isNotBlank() } ?: return null
            val modelIds = node.path("ids").mapNotNull {
                runCatching { UUID.fromString(it.asText()) }.getOrNull()
            }
            withRefs(text, matters, modelIds)
        } catch (e: Exception) {
            log.debug("Q&A model call failed: {}", e.javaClass.simpleName)
            null
        }
    }

    private fun chatRole(): ModelRole? = when {
        router.available(ModelRole.VISION) -> ModelRole.VISION
        router.available(ModelRole.TEXT) -> ModelRole.TEXT
        else -> null
    }

    private data class MatterSnap(
        val id: UUID,
        val title: String,
        val childId: UUID?,
        val whenStamp: String?,
        val kind: String,
        val parentToDo: List<String>,
        val childNeeds: List<String>,
        val summary: String?,
        val focus: Boolean = false,
    )

    private fun collectMatters(
        keywords: List<String>,
        window: DateWindow?,
        accessible: List<UUID>,
        cases: Map<UUID, SchoolCase>,
        detail: Boolean,
        focus: UUID?,
    ): List<MatterSnap> {
        val events = if (window != null) {
            eventsInScope(window, accessible, cases)
        } else {
            val now = clock.instant()
            val upcoming = eventsInScope(null, accessible, cases).filter { !it.startTime.isBefore(now) }
            val keyworded = if (keywords.isEmpty()) emptyList() else
                eventsInScope(null, accessible, cases).filter { score(it.title, keywords) >= 0.3 }
            (upcoming + keyworded).distinctBy { it.id }
        }
        val actions = actionRepo.findAll()
            .filter { visible(null, it.caseId, accessible, cases) }
            .filter { it.status != ActionStatus.CANCELLED && it.status != ActionStatus.COMPLETED }
            .filter { window == null || it.dueAt == null || window.contains(it.dueAt!!, berlin) }
        val reqs = requirementRepo.findAll()
            .filter { visible(null, it.caseId, accessible, cases) }
            .filter { it.status != RequirementStatus.CANCELLED && it.status != RequirementStatus.DONE }
            .filter { window == null || it.relevantOn == null || window.contains(it.relevantOn!!) }

        val caseIds = linkedSetOf<UUID>()
        if (focus != null) caseIds += focus
        events.forEach { caseIds += it.caseId }
        actions.forEach { caseIds += it.caseId }
        reqs.forEach { caseIds += it.caseId }
        if (window == null && keywords.isNotEmpty()) {
            cases.values.filter { score("${it.title} ${it.summary ?: ""}", keywords) >= 0.3 }
                .forEach { caseIds += it.id }
        }

        return caseIds.take(MAX_MATTERS).mapNotNull { id ->
            if (id == focus) matterSnap(id, accessible, cases, detail, focus = true)
            else matterSnap(id, accessible, cases, detail, false, events, actions, reqs)
        }
    }

    private fun matterSnap(
        id: UUID,
        accessible: List<UUID>,
        cases: Map<UUID, SchoolCase>,
        detail: Boolean,
        focus: Boolean = false,
        events: List<de.schoolagent.domain.model.SchoolEvent>? = null,
        actions: List<ParentAction>? = null,
        reqs: List<ChildRequirement>? = null,
    ): MatterSnap? {
        val schoolCase = cases[id] ?: return null
        if (!visible(schoolCase.childId, id, accessible, cases)) return null
        val caseEvents = (events ?: eventRepo.findAll().filter { it.caseId == id && it.childId?.let { c -> accessible.contains(c) } != false })
            .filter { it.caseId == id }
            .sortedBy { it.startTime }
        val headline = caseEvents.firstOrNull { it.eventType != EventType.BRING_ITEM && !it.title.lowercase().startsWith("bring") }
        val title = headline?.title ?: schoolCase.title
        val caseActions = (actions ?: actionRepo.findAll())
            .filter { !schoolCase.isInfo() && !schoolCase.isClosed() && it.caseId == id && it.status != ActionStatus.CANCELLED && it.status != ActionStatus.COMPLETED }
            .sortedBy { it.dueAt ?: Instant.MAX }
        val caseReqs = (reqs ?: requirementRepo.findAll())
            .filter { !schoolCase.isInfo() && !schoolCase.isClosed() && it.caseId == id && it.status != RequirementStatus.CANCELLED && it.status != RequirementStatus.DONE }
        val caseNeeds = if (schoolCase.isInfo() || schoolCase.isClosed()) emptyList() else caseReqs.map { it.description } +
            caseEvents.filter { it.eventType == EventType.BRING_ITEM || it.title.lowercase().startsWith("bring") }
                .map { it.title }
        val whenStamp = headline?.let { formatWhen(it) }
            ?: caseActions.firstOrNull()?.dueAt?.atZone(berlin)?.toLocalDate()?.toString()
            ?: caseReqs.firstOrNull()?.relevantOn?.toString()
        val kind = when {
            headline != null -> "event"
            caseActions.isNotEmpty() -> "action"
            else -> "letter"
        }
        return MatterSnap(
            id = id,
            title = title,
            childId = schoolCase.childId,
            whenStamp = whenStamp,
            kind = kind,
            parentToDo = caseActions.map { it.title + if (it.required) "" else " (optional)" },
            childNeeds = caseNeeds.distinct(),
            summary = if (detail) schoolCase.summary?.takeIf { it.isNotBlank() }?.take(200) else null,
            focus = focus,
        )
    }

    private fun mattersJson(
        matters: List<MatterSnap>,
        window: DateWindow?,
        accessible: List<UUID>,
        detail: Boolean,
    ): String {
        val today = clock.instant().atZone(berlin).toLocalDate()
        val factory = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance
        val root = factory.objectNode()
        root.put("today", today.toString())
        root.putArray("children").apply {
            childRepo.findAll().filter { it.id in accessible }.forEach { child ->
                addObject().apply {
                    put("id", child.id.toString())
                    put("name", child.name)
                    child.className?.let { put("class", it) }
                    child.school?.let { put("school", it) }
                }
            }
        }
        root.put("mode", if (detail) "DETAIL" else "OVERVIEW")
        root.put("selection_limited", matters.size >= MAX_MATTERS)
        if (window != null) root.put("window", window.describe())

        val holidays = factory.arrayNode()
        val holidayRows = if (window != null) breaksInWindow(window, accessible) else upcomingBreaks(false, accessible)
        holidayRows.take(6).forEach { h ->
            holidays.addObject().apply {
                put("name", h.name)
                put("from", h.startDate.toString())
                put("to", h.endDate.toString())
                put("type", h.entryType.name.lowercase())
            }
        }
        root.set<com.fasterxml.jackson.databind.JsonNode>("holidays", holidays)

        val mattersNode = factory.arrayNode()
        matters.forEach { m ->
            mattersNode.addObject().apply {
                put("id", m.id.toString())
                m.childId?.let { put("child_id", it.toString()) }
                put("title", m.title)
                if (m.whenStamp != null) put("when", m.whenStamp)
                put("kind", m.kind)
                if (m.focus) put("focus", true)
                putArray("parent_to_do").apply { m.parentToDo.forEach { add(it) } }
                putArray("child_needs").apply { m.childNeeds.forEach { add(it) } }
                if (detail) m.summary?.let { put("summary", it) }
            }
        }
        root.set<com.fasterxml.jackson.databind.JsonNode>("matters", mattersNode)
        return com.fasterxml.jackson.databind.ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(root)
    }

    private fun formatMatter(snap: MatterSnap, detail: Boolean): String = buildString {
        append(snap.title)
        snap.whenStamp?.let { append(" — $it") }
        if (detail) {
            snap.parentToDo.forEach { append("\n• $it") }
            snap.childNeeds.forEach { append("\n🎒 $it") }
            if (snap.parentToDo.isEmpty() && snap.childNeeds.isEmpty()) {
                snap.summary?.let { append("\n$it") }
            }
        }
    }

    private fun withRefs(text: String, matters: List<MatterSnap>, modelIds: List<UUID> = emptyList()): ChatAnswer {
        val byId = matters.associateBy { it.id }
        val fromModel = modelIds.mapNotNull { byId[it] }
        val picked = if (fromModel.isNotEmpty()) {
            fromModel
        } else {
            matters.filter { m ->
                text.contains(m.title, ignoreCase = true) || TitleSimilarity.similar(text, m.title)
            }
        }
        return ChatAnswer(text, picked.distinctBy { it.id }.take(6).map { ChatRef(it.id, it.title) })
    }

    private fun formatWhen(event: de.schoolagent.domain.model.SchoolEvent): String {
        val whenAt = event.startTime.atZone(berlin)
        return if (event.allDay || whenAt.toLocalTime() == java.time.LocalTime.MIDNIGHT) {
            whenAt.toLocalDate().toString()
        } else {
            whenAt.format(dtFmt)
        }
    }

    private fun fallbackList(
        keywords: List<String>,
        window: DateWindow?,
        accessible: List<UUID>,
        cases: Map<UUID, SchoolCase>,
    ): ChatAnswer? {
        val mentions = mentionHits(keywords, window, accessible, cases)
        if (mentions.isNotEmpty()) {
            val matters = collectMatters(keywords, window, accessible, cases, detail = true, focus = null)
            return withRefs(mentions.joinToString("\n"), matters)
        }
        if (window != null) {
            val events = eventsInScope(window, accessible, cases).take(12)
            if (events.isNotEmpty()) {
                val matters = collectMatters(emptyList(), window, accessible, cases, detail = false, focus = null)
                return withRefs(events.joinToString("\n") { formatEvent(it) }, matters)
            }
        }
        return null
    }

    private fun mentionHits(
        keywords: List<String>,
        window: DateWindow?,
        accessible: List<UUID>,
        cases: Map<UUID, SchoolCase>,
    ): List<String> {
        if (keywords.isEmpty()) return emptyList()
        val hits = mutableListOf<Pair<Double, String>>()
        val sourcesById = sourceRepo.findAll().associateBy { it.id }

        for (e in evidenceRepo.findAll()) {
            val schoolCase = cases[e.caseId] ?: continue
            if (!visible(schoolCase.childId, schoolCase.id, accessible, cases)) continue
            if (window != null && !window.contains(e.createdAt, berlin)) continue
            val blob = "${e.subject} ${e.payload ?: ""}"
            val s = score(blob, keywords)
            if (s >= 0.3) {
                val src = sourcesById[e.sourceId]
                val letter = src?.title?.takeIf { it.isNotBlank() } ?: schoolCase.title
                val whenOn = src?.receivedAt?.atZone(berlin)?.toLocalDate()?.format(dayFmt)
                hits += s to buildString {
                    append("📄 “${e.subject.take(140)}”")
                    append(" — in $letter")
                    whenOn?.let { append(" ($it)") }
                }
            }
        }

        for (src in sourcesById.values) {
            val caseId = src.caseId ?: continue
            val schoolCase = cases[caseId] ?: continue
            if (!visible(schoolCase.childId, schoolCase.id, accessible, cases)) continue
            if (window != null && src.receivedAt != null && !window.contains(src.receivedAt!!, berlin)) continue
            val blob = "${src.title ?: ""} ${src.sender ?: ""} ${schoolCase.title} ${schoolCase.summary ?: ""}"
            val s = score(blob, keywords)
            if (s >= 0.3) {
                val whenOn = src.receivedAt?.atZone(berlin)?.toLocalDate()?.format(dayFmt)
                hits += s to buildString {
                    append("✉️ ${src.title ?: schoolCase.title}")
                    src.sender?.let { append(" (from $it)") }
                    whenOn?.let { append(" — $it") }
                }
            }
        }

        for (c in cases.values) {
            if (c.childId != null && !accessible.contains(c.childId)) continue
            if (window != null && !window.contains(c.openedAt, berlin) && !window.contains(c.updatedAt, berlin)) continue
            val s = score("${c.title} ${c.summary ?: ""}", keywords)
            if (s >= 0.3) {
                hits += s to "📁 ${c.title}${c.summary?.let { " — ${it.take(100)}" } ?: ""}"
            }
        }

        return hits.sortedByDescending { it.first }.map { it.second }.distinct().take(8)
    }

    private fun eventsInScope(window: DateWindow?, accessible: List<UUID>, cases: Map<UUID, SchoolCase>) =
        eventRepo.findAll()
            .filter { visible(it.childId, it.caseId, accessible, cases) }
            .filter { it.status != EventStatus.CANCELLED }
            .filter { window == null || window.contains(it.startTime, berlin) }
            .sortedBy { it.startTime }

    private fun compactAgenda(
        window: DateWindow,
        accessible: List<UUID>,
        cases: Map<UUID, SchoolCase>,
    ): String? {
        val lines = mutableListOf<String>()
        breaksInWindow(window, accessible).take(4).forEach { lines += formatBreak(it) }
        val events = collapseEvents(
            eventsInScope(window, accessible, cases)
                .filter { it.eventType != EventType.BRING_ITEM }
                .filter { !it.title.lowercase().startsWith("bring") },
        )
        events.take(6).forEach { lines += formatEvent(it) }
        val eventCases = events.map { it.caseId }.toSet()
        collapseActions(
            actionRepo.findAll()
                .filter { visible(null, it.caseId, accessible, cases) }
                .filter { it.status != ActionStatus.CANCELLED && it.status != ActionStatus.COMPLETED }
                .filter { it.dueAt != null && window.contains(it.dueAt!!, berlin) }
                .sortedBy { it.dueAt },
        )
            .filter { it.caseId !in eventCases }
            .take(4)
            .forEach { a ->
                val due = a.dueAt!!.atZone(berlin).toLocalDate().format(dayFmt)
                lines += "📋 $due — ${a.title}"
            }
        return lines.distinct().take(8).joinToString("\n").takeIf { it.isNotBlank() }
    }

    private fun collapseEvents(events: List<de.schoolagent.domain.model.SchoolEvent>): List<de.schoolagent.domain.model.SchoolEvent> {
        val kept = mutableListOf<de.schoolagent.domain.model.SchoolEvent>()
        for (e in events) {
            if (kept.any { it.caseId == e.caseId || TitleSimilarity.similar(it.title, e.title) }) continue
            kept += e
        }
        return kept
    }

    private fun collapseActions(actions: List<de.schoolagent.domain.model.ParentAction>): List<de.schoolagent.domain.model.ParentAction> {
        val kept = mutableListOf<de.schoolagent.domain.model.ParentAction>()
        for (a in actions) {
            if (kept.any { it.caseId == a.caseId || TitleSimilarity.similar(it.title, a.title) }) continue
            kept += a
        }
        return kept
    }

    private fun formatEvent(event: de.schoolagent.domain.model.SchoolEvent) = buildString {
        append("📅 ${event.title}")
        val whenAt = event.startTime.atZone(berlin)
        val stamp = if (event.allDay || whenAt.toLocalTime() == java.time.LocalTime.MIDNIGHT) {
            whenAt.toLocalDate().format(dayFmt)
        } else {
            whenAt.format(dtFmt)
        }
        append(" — $stamp")
        event.location?.let { append(", $it") }
    }

    private fun formatBreak(entry: de.schoolagent.domain.model.SchoolCalendarEntry): String {
        val start = entry.startDate.format(dayFmt)
        val end = entry.endDate.format(dayFmt)
        val range = if (entry.startDate == entry.endDate) start else "$start – $end"
        return "🏫 $range — ${entry.name}"
    }

    private fun upcomingBreaks(
        vacationsOnly: Boolean,
        accessible: List<UUID>,
    ): List<de.schoolagent.domain.model.SchoolCalendarEntry> {
        val today = clock.instant().atZone(berlin).toLocalDate()
        return calendarRepo.findAll()
            .filter { it.childId == null || accessible.contains(it.childId) }
            .filter { !it.endDate.isBefore(today) }
            .filter { !vacationsOnly || it.entryType == de.schoolagent.domain.model.CalendarEntryType.VACATION }
            .sortedWith(compareBy({ it.startDate }, { it.endDate }, { it.name }))
            .take(5)
    }

    private fun breaksInWindow(
        window: DateWindow,
        accessible: List<UUID>,
    ): List<de.schoolagent.domain.model.SchoolCalendarEntry> =
        calendarRepo.findAll()
            .filter { it.childId == null || accessible.contains(it.childId) }
            .filter { window.overlaps(it.startDate, it.endDate) }
            .sortedBy { it.startDate }

    private fun looksLikeBreak(q: String): Boolean {
        val t = q.lowercase()
        return looksLikeVacationWord(t) ||
            listOf("holiday", "holidays", "festivo", "festivos", "bridge day", "no school", "sin cole")
                .any { t.contains(it) }
    }

    private fun looksLikeVacationWord(q: String): Boolean {
        val t = q.lowercase()
        return listOf("vacation", "vacations", "ferien", "vacaciones", "half term").any { t.contains(it) }
    }

    private fun keywords(question: String): List<String> =
        question.lowercase().split(Regex("[^\\p{L}\\p{N}]+"))
            .filter { it.length > 2 && it !in stopWords }
            .distinct()

    private fun score(text: String, keywords: List<String>): Double {
        if (keywords.isEmpty()) return 0.0
        val lower = text.lowercase()
        val words = lower.split(Regex("[^\\p{L}\\p{N}]+")).toSet()
        var hit = 0.0
        var weight = 0.0
        for (kw in keywords) {
            val w = kw.length.toDouble()
            weight += w
            if (words.contains(kw) || (kw.length >= 5 && lower.contains(kw))) hit += w
        }
        return if (weight == 0.0) 0.0 else hit / weight
    }

    private fun scoredCase(c: SchoolCase, keywords: List<String>): Double {
        val s = score("${c.title} ${c.summary ?: ""}", keywords)
        return if (s > 0) s else c.updatedAt.epochSecond.toDouble() / 1_000_000_000_000.0
    }

    private fun looksLikeMention(q: String): Boolean {
        val needles = listOf(
            "talk about", "talking about", "mention", "say about", "said about",
            "any place", "letter about", "where did", "where do", "did they",
            "habl", "mencion", "carta",
        )
        return needles.any { q.contains(it) }
    }

    private fun looksLikeAgenda(q: String): Boolean {
        val t = q.lowercase()
        val needles = listOf(
            "what is on", "what's on", "whats on", "what events", "anything on",
            "happening", "coming up", "calendar", "termine", "que hay", "qué hay",
            "next week", "this week", "this month", "proxima semana", "próxima semana",
            "esta semana", "nächste woche", "diese woche",
        )
        return needles.any { t.contains(it) } ||
            (t.contains("event") && (t.contains("before") || t.contains("after") || t.contains("between") || t.contains("in ")))
    }

    private fun wantsDetail(question: String): Boolean {
        val q = question.lowercase()
        return listOf(
            "more", "detail", "details", "exactly", "what should", "what do we need",
            "bring", "how much", "where is", "tell me", "about that", "about the",
            "más", "mas detalle", "detalles", "qué hay que", "que hay que llevar",
        ).any { q.contains(it) }
    }

    private fun visible(eventChild: UUID?, caseId: UUID, accessible: List<UUID>, cases: Map<UUID, SchoolCase>): Boolean {
        val schoolCase = cases[caseId] ?: return false
        return (eventChild == null || eventChild in accessible) &&
            (schoolCase.childId == null || schoolCase.childId in accessible)
    }

    companion object {
        private const val MAX_MATTERS = 50
        private val SMALL_TALK = setOf(
            "hi", "hello", "hey", "thanks", "thank you", "ok", "okay", "hola",
            "gracias", "ciao", "moin", "hallo", "danke", "yes", "no", "sí", "si",
            "bueno", "vale",
        )
    }
}
