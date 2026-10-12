package de.schoolagent.api

import de.schoolagent.domain.model.AppUser
import de.schoolagent.domain.model.Child
import de.schoolagent.domain.repo.AppUserRepository
import de.schoolagent.domain.repo.ChildRepository
import de.schoolagent.security.KidAccessDeniedException
import de.schoolagent.security.KidAccessService
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.core.userdetails.UserDetails
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.time.Clock
import java.util.UUID

data class KidDto(
    val id: UUID,
    val name: String,
    val school: String?,
    val className: String?,
    val color: String,
    val assignedAccounts: List<String>,
)

/** Kids visible to the signed-in account (assigned kids; all for admin). */
@RestController
@RequestMapping("/api/kids")
class KidsController(
    private val kids: ChildRepository,
    private val users: AppUserRepository,
    private val access: KidAccessService,
) {

    @GetMapping
    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    fun list(@AuthenticationPrincipal user: UserDetails): List<KidDto> =
        kids.findAll().filter { access.canAccess(user, it.id) }
            .sortedBy { it.name }
            .map { it.toDto() }

    private fun Child.toDto() = KidDto(
        id, name, school, className, color,
        accounts.map { it.name }.sorted(),
    )
}

/** Admin-only kid management. */
@RestController
@RequestMapping("/api/admin/kids")
class AdminKidsController(
    private val kids: ChildRepository,
    private val users: AppUserRepository,
    private val clock: Clock,
) {

    data class KidRequest(
        val name: String,
        val school: String? = null,
        val className: String? = null,
        val color: String = "#2563eb",
        val accountNames: List<String> = emptyList(),
    )

    @GetMapping
    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    fun list(): List<KidDto> = kids.findAll().sortedBy { it.name }.map {
        KidDto(it.id, it.name, it.school, it.className, it.color, it.accounts.map { a -> a.name }.sorted())
    }

    @PostMapping
    fun create(@RequestBody request: KidRequest): KidDto {
        if (request.name.trim().length < 2) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "name too short")
        val kid = kids.save(
            Child(
                name = request.name.trim(),
                school = request.school?.trim(),
                className = request.className?.trim(),
                color = request.color,
                createdAt = clock.instant(),
                updatedAt = clock.instant(),
            ),
        )
        assign(kid, request.accountNames)
        return kid.let { KidDto(it.id, it.name, it.school, it.className, it.color, it.accounts.map { a -> a.name }.sorted()) }
    }

    @PutMapping("/{id}")
    fun update(@PathVariable id: UUID, @RequestBody request: KidRequest): KidDto {
        val kid = kids.findById(id).orElse(null) ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "kid not found")
        kid.name = request.name.trim()
        kid.school = request.school?.trim()
        kid.className = request.className?.trim()
        kid.color = request.color
        kid.updatedAt = clock.instant()
        assign(kid, request.accountNames)
        val saved = kids.save(kid)
        return KidDto(saved.id, saved.name, saved.school, saved.className, saved.color, saved.accounts.map { a -> a.name }.sorted())
    }

    @DeleteMapping("/{id}")
    fun delete(@PathVariable id: UUID) {
        val kid = kids.findById(id).orElse(null) ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "kid not found")
        if (kids.count() <= 1) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "at least one kid is required")
        kids.delete(kid)
    }

    private fun assign(kid: Child, accountNames: List<String>) {
        val wanted = accountNames.map { it.trim().lowercase() }.filter { it.isNotBlank() }.toSet()
        val accounts = users.findAll()
        kid.accounts.clear()
        kid.accounts.addAll(accounts.filter { it.name in wanted })
        kids.save(kid)
    }
}
