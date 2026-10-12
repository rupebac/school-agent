package de.schoolagent.security

import de.schoolagent.domain.model.AppUser
import de.schoolagent.domain.repo.AppUserRepository
import de.schoolagent.domain.repo.ChildRepository
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.core.userdetails.UserDetails
import org.springframework.stereotype.Service
import java.util.UUID

/**
 * Kid-scoped authorization (multi-kid model): ADMIN sees every kid; a PARENT
 * only the kids their account is assigned to. Guards per-kid config endpoints
 * and case visibility.
 */
@Service
class KidAccessService(
    private val users: AppUserRepository,
    private val kids: ChildRepository,
) {

    fun isAdmin(user: UserDetails): Boolean =
        users.findByName(user.username)?.role == "ADMIN"

    fun accessibleKidIds(user: UserDetails): List<UUID> {
        val account = users.findByName(user.username) ?: return emptyList()
        if (account.role == "ADMIN") return kids.findAll().map { it.id }
        return account.kids.map { it.id }
    }

    fun canAccess(user: UserDetails, childId: UUID): Boolean =
        accessibleKidIds(user).contains(childId)

    fun account(userDetails: UserDetails): AppUser? = users.findByName(userDetails.username)

    fun requireAccess(user: UserDetails, childId: UUID?) {
        if (childId != null && !canAccess(user, childId)) {
            throw org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.FORBIDDEN, "no access to this child")
        }
    }
}

/** Thrown when an account touches a kid they are not assigned to. */
class KidAccessDeniedException : RuntimeException("no access to this kid")
