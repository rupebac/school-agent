package de.schoolagent.connectors.elternportal

import de.schoolagent.connectors.school.*
import de.schoolagent.security.KidAccessService
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.core.userdetails.UserDetails
import org.springframework.web.bind.annotation.*
import java.util.UUID

/** Compatibility routes use the same exclusive connection, authorization and ingestion path. */
@RestController
@RequestMapping("/api/elternportal")
class ElternportalController(
    private val config: SchoolConnectionService,
    private val school: SchoolConnectionController,
    private val access: KidAccessService,
) {
    data class SaveRequest(val url: String, val username: String, val password: String?)
    @GetMapping("/status")
    fun status(@RequestParam kid: UUID, @AuthenticationPrincipal user: UserDetails) = school.status(kid, user)
    @PutMapping("/config")
    fun save(@RequestParam kid: UUID, @AuthenticationPrincipal user: UserDetails, @RequestBody request: SaveRequest) =
        school.save(kid, user, SchoolConnectionService.SaveRequest(SchoolProvider.ELTERNPORTAL, request.username, request.password, request.url))
    @DeleteMapping("/config")
    fun clear(@RequestParam kid: UUID, @AuthenticationPrincipal user: UserDetails) {
        requirePortal(kid, user)
        school.clear(kid, user)
    }
    @PostMapping("/test")
    fun test(@RequestParam kid: UUID, @AuthenticationPrincipal user: UserDetails): Map<String, Any> {
        requirePortal(kid, user)
        return school.test(kid, user)
    }
    @PostMapping("/sync")
    fun sync(@RequestParam kid: UUID, @AuthenticationPrincipal user: UserDetails): Map<String, Any> {
        requirePortal(kid, user)
        return school.sync(kid, user)
    }
    private fun requirePortal(kid: UUID, user: UserDetails) {
        access.requireAccess(user, kid)
        if (config.provider(kid) == SchoolProvider.SCHULMANAGER) throw org.springframework.web.server.ResponseStatusException(
            org.springframework.http.HttpStatus.CONFLICT, "This child uses Schulmanager Online")
    }
}
