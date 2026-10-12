package de.schoolagent.api

import de.schoolagent.documents.DocumentStore
import de.schoolagent.domain.repo.SourceRepository
import de.schoolagent.domain.service.FamilyInboxService
import org.springframework.http.*
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.core.userdetails.UserDetails
import org.springframework.web.bind.annotation.*
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

@RestController
@RequestMapping("/api/cases")
class OriginalSourceController(private val inbox: FamilyInboxService, private val sources: SourceRepository, private val documents: DocumentStore) {
    @GetMapping("/{id}/sources/{sourceId}/original")
    fun original(@PathVariable id: UUID, @PathVariable sourceId: UUID, @AuthenticationPrincipal user: UserDetails): ResponseEntity<ByteArray> {
        inbox.accessible(id, user)
        val source = sources.findById(sourceId).orElse(null)?.takeIf { it.caseId == id }
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND)
        val bytes = source.storageRef?.let { documents.get(it) } ?: throw ResponseStatusException(HttpStatus.NOT_FOUND)
        val extension = when { source.mimeType == "application/pdf" -> "pdf"; source.mimeType == "message/rfc822" -> "eml"; source.mimeType == "image/jpeg" -> "jpg"; source.mimeType == "image/png" -> "png"; else -> "txt" }
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_OCTET_STREAM)
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"school-document.$extension\"")
            .header("X-Content-Type-Options", "nosniff").cacheControl(CacheControl.noStore()).body(bytes)
    }
}
