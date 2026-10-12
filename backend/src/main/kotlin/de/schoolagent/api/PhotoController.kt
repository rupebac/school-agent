package de.schoolagent.api

import de.schoolagent.ingestion.PhotoIngestionService
import de.schoolagent.security.KidAccessService
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.core.userdetails.UserDetails
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

/** Inbox / WhatsApp photo of a paper slip → case. */
@RestController
@RequestMapping("/api/cases")
class PhotoController(
    private val photos: PhotoIngestionService,
    private val access: KidAccessService,
) {

    @PostMapping("/photo", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    fun upload(
        @RequestParam file: MultipartFile,
        @RequestParam(required = false) kid: UUID?,
        @AuthenticationPrincipal user: UserDetails,
    ): CaseSummaryDto {
        val account = access.account(user)
            ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "unknown account")
        if (kid != null && !access.canAccess(user, kid)) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "no access to this kid")
        }
        val bytes = file.bytes
        val mime = file.contentType ?: "application/octet-stream"
        val ingested = try {
            photos.ingest(bytes, mime, file.originalFilename, caption = null, sender = account, childId = kid)
        } catch (e: IllegalArgumentException) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, e.message)
        }
        if (!ingested.duplicate) photos.processAsync(ingested.schoolCase.id)
        val c = ingested.schoolCase
        return c.toSummary(openActions = 0, childId = c.childId)
    }
}
