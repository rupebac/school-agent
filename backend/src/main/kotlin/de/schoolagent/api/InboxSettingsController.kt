package de.schoolagent.api

import de.schoolagent.domain.service.FamilyInboxService
import de.schoolagent.inbox.InboxSettings
import de.schoolagent.inbox.InboxSettingsService
import de.schoolagent.security.KidAccessService
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.core.userdetails.UserDetails
import org.springframework.web.bind.annotation.*
import org.springframework.web.server.ResponseStatusException

@RestController
@RequestMapping("/api/inbox/settings")
class InboxSettingsController(private val settings: InboxSettingsService, private val access: KidAccessService) {
    @GetMapping
    fun current() = settings.current()

    @PutMapping
    fun update(@AuthenticationPrincipal user: UserDetails, @RequestBody body: InboxSettings): InboxSettings {
        if (!access.isAdmin(user)) throw ResponseStatusException(HttpStatus.FORBIDDEN, "Admin access required")
        if (body.archiveAfterWeeks !in 1..52) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Archive period must be between 1 and 52 weeks")
        return settings.update(body.archiveAfterWeeks, FamilyInboxService.actor(user))
    }
}
