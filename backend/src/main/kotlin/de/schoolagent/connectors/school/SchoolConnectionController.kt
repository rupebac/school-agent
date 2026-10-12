package de.schoolagent.connectors.school

import de.schoolagent.connectors.schulmanager.SchulmanagerSession
import de.schoolagent.ingestion.SchoolIngestionService
import de.schoolagent.security.KidAccessService
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.core.userdetails.UserDetails
import org.springframework.web.bind.annotation.*
import java.util.UUID

@RestController
@RequestMapping("/api/school-connection")
class SchoolConnectionController(
    private val config: SchoolConnectionService,
    private val ingestion: SchoolIngestionService,
    private val access: KidAccessService,
) {
    @GetMapping("/status")
    fun status(@RequestParam kid: UUID, @AuthenticationPrincipal user: UserDetails): SchoolConnectionService.Status {
        access.requireAccess(user, kid)
        return config.status(kid)
    }
    @PutMapping("/config")
    fun save(@RequestParam kid: UUID, @AuthenticationPrincipal user: UserDetails, @RequestBody request: SchoolConnectionService.SaveRequest): SchoolConnectionService.Status {
        access.requireAccess(user, kid)
        return try { config.save(kid, request) } catch (e: IllegalArgumentException) {
            throw org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST, e.message)
        }
    }
    @DeleteMapping("/config")
    fun clear(@RequestParam kid: UUID, @AuthenticationPrincipal user: UserDetails) {
        access.requireAccess(user, kid)
        config.clear(kid)
    }
    @PostMapping("/discover")
    fun discover(@RequestParam kid: UUID, @AuthenticationPrincipal user: UserDetails, @RequestBody request: SchoolConnectionService.SaveRequest): Map<String, Any> {
        access.requireAccess(user, kid)
        return result {
            require(request.provider == SchoolProvider.SCHULMANAGER) { "Child discovery is only needed for Schulmanager" }
            val saved = config.credentials(kid)?.takeIf { it.provider == request.provider && it.username == request.username.trim() }
            val password = request.password?.takeIf(String::isNotBlank) ?: saved?.password
                ?: throw IllegalArgumentException("Enter a password first")
            val identity = ingestion.discover(SchoolCredentials(request.provider, SchulmanagerSession.BASE_URL, request.username.trim(), password))
            mapOf("identity" to identity)
        }
    }
    @PostMapping("/test")
    fun test(@RequestParam kid: UUID, @AuthenticationPrincipal user: UserDetails): Map<String, Any> {
        access.requireAccess(user, kid)
        return result {
            val test = ingestion.testConnection(kid)
            mapOf("briefsFound" to test.briefsFound, "identity" to test.identity)
        }
    }
    @PostMapping("/sync")
    fun sync(@RequestParam kid: UUID, @AuthenticationPrincipal user: UserDetails): Map<String, Any> {
        access.requireAccess(user, kid)
        return result {
            val sync = ingestion.syncAndProcess(kid)
            mapOf("briefsSeen" to sync.briefsSeen, "ingested" to sync.ingested, "failed" to sync.failed, "recordsSeen" to sync.recordsSeen, "recordsChanged" to sync.recordsChanged)
        }
    }
    private fun result(block: () -> Map<String, Any>): Map<String, Any> = try { mapOf("ok" to true) + block() }
        catch (e: Exception) { mapOf("ok" to false, "error" to when (e) {
            is SchoolConnectionException, is IllegalArgumentException -> (e.message ?: "Check the school connection")
            else -> "Could not read the school portal. Please test the connection again."
        }) }
}
