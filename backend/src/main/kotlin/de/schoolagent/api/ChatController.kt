package de.schoolagent.api

import de.schoolagent.domain.repo.AppUserRepository
import de.schoolagent.whatsapp.ChatTurn
import de.schoolagent.whatsapp.QuestionAnsweringService
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.core.userdetails.UserDetails
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException

/** Read-only live chat (spec §43). Does not mutate cases, actions, or mute flags. */
@RestController
@RequestMapping("/api")
class ChatController(
    private val qa: QuestionAnsweringService,
    private val users: AppUserRepository,
) {
    data class ChatMessageDto(val role: String? = null, val content: String? = null)
    data class ChatRequest(val message: String? = null, val history: List<ChatMessageDto>? = null, val caseId: String? = null, val kid: java.util.UUID? = null)
    data class ChatRefDto(val id: java.util.UUID, val title: String)
    data class ChatResponse(val answer: String, val refs: List<ChatRefDto> = emptyList())

    @PostMapping("/chat")
    fun chat(
        @RequestBody request: ChatRequest,
        @AuthenticationPrincipal user: UserDetails,
    ): ChatResponse {
        val account = users.findByName(user.username)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "user not found")
        val message = request.message?.trim().orEmpty()
        if (message.isEmpty()) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "message required")
        val history = (request.history ?: emptyList())
            .map { ChatTurn(it.role?.trim()?.lowercase().orEmpty().ifBlank { "user" }, it.content?.trim().orEmpty()) }
            .filter { it.content.isNotBlank() && it.role in setOf("user", "assistant") }
            .takeLast(8)
        val focus = request.caseId?.trim()?.let { runCatching { java.util.UUID.fromString(it) }.getOrNull() }
        val result = qa.answer(message, account, history, focus, request.kid)
        return ChatResponse(
            answer = result.text,
            refs = result.refs.map { ChatRefDto(it.id, it.title) },
        )
    }
}
