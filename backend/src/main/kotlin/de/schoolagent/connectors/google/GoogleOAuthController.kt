package de.schoolagent.connectors.google

import de.schoolagent.connectors.calendar.CalendarInfoDto
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.core.userdetails.UserDetails
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/** Google connection + calendar setup endpoints for the Settings screen (spec §28). */
@RestController
@RequestMapping("/api/google")
class GoogleOAuthController(
    private val oauth: GoogleOAuthService,
    private val calendarSetup: GoogleCalendarSetupService,
    private val props: GoogleProperties,
    private val access: de.schoolagent.security.KidAccessService,
    private val gmail: de.schoolagent.connectors.gmail.GmailRestClient,
) {

    data class StatusDto(
        val clientConfigured: Boolean,
        val connected: Boolean,
        val calendarId: String,
        val pollingEnabled: Boolean,
        val authorizeUrl: String?,
        val mailbox: String? = null,
    )

    data class CalendarSetupDto(
        val connected: Boolean,
        val configuredCalendarId: String,
        val configuredIsPrimary: Boolean,
        val expectedName: String,
        val schoolCalendarFound: CalendarInfoDto?,
        val calendars: List<CalendarInfoDto>,
    )

    data class EnsureCalendarRequest(val shareWith: List<String> = emptyList())

    @GetMapping("/status")
    fun status(
        @AuthenticationPrincipal user: UserDetails,
        @RequestParam(required = false) kid: java.util.UUID?,
    ): StatusDto {
        requireConnectionAccess(user, kid)
        val configured = oauth.clientConfigured()
        return StatusDto(
            clientConfigured = configured,
            connected = oauth.hasRefreshToken(kid),
            calendarId = calendarSetup.resolveCalendarId(kid),
            pollingEnabled = props.pollingEnabled,
            mailbox = if (oauth.hasRefreshToken(kid)) runCatching { gmail.mailbox(kid) }.getOrNull() else null,
            authorizeUrl = if (configured) oauth.authorizationUrl(user.username, kid) else null,
        )
    }

    /** Redirect to Google's consent screen (opened from the frontend). */
    @GetMapping("/authorize")
    fun authorize(
        @AuthenticationPrincipal user: UserDetails,
        @RequestParam(required = false) kid: java.util.UUID?,
    ): ResponseEntity<Void> {
        requireConnectionAccess(user, kid)
        return ResponseEntity.status(302).header("Location", oauth.authorizationUrl(user.username, kid)).build<Void>()
    }

    /**
     * OAuth callback, hit by Google's redirect (no Authorization header possible)
     * — permitted anonymously in SecurityConfig and validated by the single-use state.
     * Redirects back into the app with a result flag.
     */
    @GetMapping("/callback")
    fun callback(
        @RequestParam(required = false) code: String?,
        @RequestParam state: String,
        @RequestParam(name = "error", required = false) error: String?,
    ): ResponseEntity<Void> {
        if (error != null) {
            return redirect("googleError=" + encode("Google authorization was not completed. Try connecting again."))
        }
        return try {
            val result = oauth.handleCallback(code ?: error("Missing authorization code"), state)
            runCatching { calendarSetup.ensureSchoolCalendar(result.childId, emptyList()) }
            redirect("google=connected" + (result.childId?.let { "&kid=$it" } ?: ""))
        } catch (e: Exception) {
            redirect("googleError=" + encode("Google connection failed. Check the client settings and callback address, then connect again."))
        }
    }

    @DeleteMapping("/connection")
    fun disconnect(@RequestParam(required = false) kid: java.util.UUID?, @AuthenticationPrincipal user: UserDetails): ResponseEntity<Void> {
        requireConnectionAccess(user, kid)
        oauth.revoke(kid)
        return ResponseEntity.noContent().build()
    }

    // ---- calendar setup (spec §4: "Jamie – Schule", shared with both parents) ----

    @GetMapping("/calendar-setup")
    fun calendarSetup(@RequestParam(required = false) kid: java.util.UUID?, @AuthenticationPrincipal user: UserDetails): CalendarSetupDto {
        requireConnectionAccess(user, kid)
        val s = calendarSetup.status(kid)
        return CalendarSetupDto(
            connected = s.connected,
            configuredCalendarId = s.configuredCalendarId,
            configuredIsPrimary = s.configuredIsPrimary,
            expectedName = s.expectedName,
            schoolCalendarFound = s.schoolCalendarFound,
            calendars = s.calendars,
        )
    }

    @PostMapping("/calendar-setup")
    fun ensureCalendar(
        @RequestParam(required = false) kid: java.util.UUID?,
        @RequestBody(required = false) request: EnsureCalendarRequest?,
        @AuthenticationPrincipal user: UserDetails,
    ): ResponseEntity<CalendarSetupDto> {
        requireConnectionAccess(user, kid)
        val shareWith = request?.shareWith ?: emptyList()
        val created = calendarSetup.ensureSchoolCalendar(kid, shareWith)
        val s = calendarSetup.status(kid)
        return ResponseEntity.ok(
            CalendarSetupDto(
                connected = s.connected,
                configuredCalendarId = s.configuredCalendarId,
                configuredIsPrimary = s.configuredIsPrimary,
                expectedName = s.expectedName,
                schoolCalendarFound = created,
                calendars = s.calendars,
            ),
        )
    }

    private fun requireConnectionAccess(user: org.springframework.security.core.userdetails.UserDetails, kid: java.util.UUID?) {
        if (kid == null && !access.isAdmin(user)) throw org.springframework.web.server.ResponseStatusException(
            org.springframework.http.HttpStatus.FORBIDDEN, "The shared Google connection requires an administrator")
        access.requireAccess(user, kid)
    }

    private fun redirect(query: String): ResponseEntity<Void> =
        ResponseEntity.status(302)
            .header("Location", props.successRedirectUri + "?" + query)
            .build<Void>()

    private fun encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8)
}
