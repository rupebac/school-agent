package de.schoolagent.api

import de.schoolagent.domain.repo.AiUsageRepository
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

/** Conversational retrieval groundwork (spec §43) + AI cost view (spec §41). */
@RestController
@RequestMapping("/api")
class SearchController(
    private val access: de.schoolagent.security.KidAccessService,
    private val searchService: de.schoolagent.search.CaseSearchService,
    private val usageRepo: AiUsageRepository,
) {

    data class SearchHit(val caseId: UUID, val title: String, val status: String, val match: String, val score: Int)

    data class UsageDto(
        val totalCalls: Int,
        val inputTokens: Int,
        val outputTokens: Int,
        val byModel: Map<String, Int>,
        val entries: Int,
    )

    @GetMapping("/search")
    fun search(@RequestParam q: String, @RequestParam(required=false) kid: UUID?,
        @org.springframework.security.core.annotation.AuthenticationPrincipal user: org.springframework.security.core.userdetails.UserDetails): List<SearchHit> {
        access.requireAccess(user, kid)
        val query = q.trim()
        if (query.length < 2 || query.length > 200) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "query must be 2–200 characters")
        return searchService.search(query, access.accessibleKidIds(user), kid)
            .map { SearchHit(it.caseId, it.title, it.status, it.match, it.score) }
    }

    @GetMapping("/ai/usage")
    fun usage(): UsageDto {
        val all = usageRepo.findAll()
        return UsageDto(
            totalCalls = all.sumOf { it.calls },
            inputTokens = all.sumOf { it.inputTokens },
            outputTokens = all.sumOf { it.outputTokens },
            byModel = all.groupingBy { it.model }.eachCount(),
            entries = all.size,
        )
    }
}
