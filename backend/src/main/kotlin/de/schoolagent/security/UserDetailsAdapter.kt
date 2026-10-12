package de.schoolagent.security

import de.schoolagent.domain.model.AppUser
import org.springframework.security.core.GrantedAuthority
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.userdetails.UserDetails

/** Wraps an AppUser as a UserDetails for internal service calls (briefings, Q&A). */
class UserDetailsAdapter(private val account: AppUser) : UserDetails {
    override fun getUsername(): String = account.name
    override fun getPassword(): String = account.passwordHash
    override fun getAuthorities(): Collection<GrantedAuthority> =
        listOf(SimpleGrantedAuthority("ROLE_${account.role}"))
    override fun isAccountNonExpired(): Boolean = true
    override fun isAccountNonLocked(): Boolean = true
    override fun isCredentialsNonExpired(): Boolean = true
    override fun isEnabled(): Boolean = true
}
