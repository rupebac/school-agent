package de.schoolagent.security

import de.schoolagent.domain.model.AppUser
import de.schoolagent.domain.repo.AppUserRepository
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.core.userdetails.UserDetails
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.provisioning.UserDetailsManager
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

/** Admin-only subaccount management (family deployment). */
@RestController
@RequestMapping("/api/admin/users")
class AdminUserController(
    private val users: AppUserRepository,
    private val encoder: PasswordEncoder,
    private val clock: Clock,
) {

    data class UserDto(
        val id: java.util.UUID,
        val name: String,
        val displayName: String,
        val role: String,
        val whatsappNumber: String?,
        val language: String,
    )

    data class CreateUserRequest(
        val name: String,
        val displayName: String,
        val password: String,
        val role: String = "PARENT",
        val whatsappNumber: String? = null,
        val language: String? = null,
    )

    data class UpdateUserRequest(
        val displayName: String? = null,
        val password: String? = null,
        val role: String? = null,
        val whatsappNumber: String? = null,
        val language: String? = null,
    )

    @GetMapping
    fun list(): List<UserDto> = users.findAll().sortedBy { it.name }.map { it.toDto() }

    @PostMapping
    fun create(@RequestBody request: CreateUserRequest): UserDto {
        val name = request.name.trim().lowercase()
        if (name.isBlank() || !name.matches(Regex("[a-z0-9._-]+"))) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid username")
        }
        if (users.findByName(name) != null) throw ResponseStatusException(HttpStatus.CONFLICT, "username exists")
        if (request.password.length < 8) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "password min 8 chars")
        val role = if (request.role == "ADMIN") "ADMIN" else "PARENT"
        val saved = users.save(
            AppUser(
                name = name,
                displayName = request.displayName.ifBlank { name },
                passwordHash = encoder.encode(request.password) ?: throw IllegalStateException("hash"),
                whatsappNumber = request.whatsappNumber?.trim()?.takeIf { it.isNotBlank() },
                role = role,
                language = de.schoolagent.i18n.UserLanguage.of(request.language).code,
                createdAt = clock.instant(),
                updatedAt = clock.instant(),
            ),
        )
        return saved.toDto()
    }

    @PutMapping("/{id}")
    fun update(@PathVariable id: java.util.UUID, @RequestBody request: UpdateUserRequest): UserDto {
        val account = users.findById(id).orElse(null)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "user not found")
        request.displayName?.let { account.displayName = it }
        request.role?.let { account.role = if (it == "ADMIN") "ADMIN" else "PARENT" }
        request.whatsappNumber?.let { account.whatsappNumber = it.trim().takeIf { s -> s.isNotBlank() } }
        request.language?.let { account.language = de.schoolagent.i18n.UserLanguage.of(it).code }
        if (!request.password.isNullOrBlank()) {
            if (request.password.length < 8) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "password min 8 chars")
            account.passwordHash = encoder.encode(request.password) ?: throw IllegalStateException("hash")
        }
        account.updatedAt = clock.instant()
        return users.save(account).toDto()
    }

    @DeleteMapping("/{id}")
    fun delete(@PathVariable id: java.util.UUID, @AuthenticationPrincipal admin: UserDetails) {
        val account = users.findById(id).orElse(null)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "user not found")
        if (account.name == admin.username) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "cannot delete yourself")
        }
        users.delete(account)
    }

    private fun AppUser.toDto() = UserDto(id, name, displayName, role, whatsappNumber, language)
}
