package de.schoolagent.security

import de.schoolagent.domain.model.AppUser
import de.schoolagent.domain.repo.AppUserRepository
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.core.userdetails.UserDetails
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.time.Clock

/** Self-service password change for the family accounts. */
@RestController
@RequestMapping("/api/account")
class AccountController(
    private val users: AppUserRepository,
    private val encoder: PasswordEncoder,
    private val clock: Clock,
    private val rememberTokens: org.springframework.security.web.authentication.rememberme.PersistentTokenRepository,
) {

    data class ChangePasswordRequest(val oldPassword: String, val newPassword: String)
    data class LanguageRequest(val language: String)
    data class WhatsappRequest(val whatsappNumber: String? = null)

    @PostMapping("/password")
    @org.springframework.transaction.annotation.Transactional
    fun changePassword(@RequestBody request: ChangePasswordRequest, @AuthenticationPrincipal user: UserDetails) {
        if (request.newPassword.length < 8) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "new password must be at least 8 characters")
        }
        val account: AppUser = users.findByName(user.username)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "user not found")
        if (!encoder.matches(request.oldPassword, account.passwordHash)) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "old password is wrong")
        }
        val hash = encoder.encode(request.newPassword)
            ?: throw ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "hash failed")
        rememberTokens.removeUserTokens(account.name)
        account.passwordHash = hash
        account.updatedAt = clock.instant()
        users.save(account)
    }

    @PostMapping("/language")
    fun changeLanguage(@RequestBody request: LanguageRequest, @AuthenticationPrincipal user: UserDetails): Map<String, String> {
        val account: AppUser = users.findByName(user.username)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "user not found")
        val lang = de.schoolagent.i18n.UserLanguage.of(request.language)
        account.language = lang.code
        account.updatedAt = clock.instant()
        users.save(account)
        return mapOf("language" to lang.code)
    }

    @PostMapping("/whatsapp")
    fun changeWhatsapp(@RequestBody request: WhatsappRequest, @AuthenticationPrincipal user: UserDetails): Map<String, String?> {
        val account: AppUser = users.findByName(user.username)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "user not found")
        account.whatsappNumber = request.whatsappNumber?.trim()?.takeIf { it.isNotBlank() }
        account.updatedAt = clock.instant()
        users.save(account)
        return mapOf("whatsappNumber" to account.whatsappNumber)
    }
}
