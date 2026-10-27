package de.schoolagent.api

import de.schoolagent.calendar.SchoolCalendarService
import de.schoolagent.domain.model.ActionAcknowledgment
import de.schoolagent.domain.model.CalendarEntryType
import de.schoolagent.domain.model.SchoolCalendarEntry
import de.schoolagent.domain.repo.ActionAcknowledgmentRepository
import de.schoolagent.domain.repo.AppUserRepository
import de.schoolagent.domain.repo.SchoolCalendarEntryRepository
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
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.time.Clock
import java.time.LocalDate
import java.util.UUID

data class CalendarEntryDto(
    val id: UUID,
    val childId: UUID?,
    val name: String,
    val startDate: LocalDate,
    val endDate: LocalDate,
    val entryType: String,
)

data class AcknowledgmentDto(val name: String, val displayName: String, val at: java.time.Instant)

/** School calendar + acknowledgment + user preferences API. */
@RestController
@RequestMapping("/api")
class SchoolCalendarController(
    private val access: KidAccessService,
    private val calendar: SchoolCalendarService,
    private val repo: SchoolCalendarEntryRepository,
) {

    @GetMapping("/school-calendar")
    fun list(
        @RequestParam from: LocalDate?,
        @RequestParam to: LocalDate?,
        @RequestParam(required=false) kid: UUID?,
        @AuthenticationPrincipal user: UserDetails,
    ): List<CalendarEntryDto> {
        access.requireAccess(user, kid)
        val allowed = access.accessibleKidIds(user)
        val start = from ?: LocalDate.now().minusMonths(1)
        val end = to ?: LocalDate.now().plusMonths(3)
        return calendar.entriesBetween(start, end).filter { (it.childId == null || it.childId in allowed) && (kid == null || it.childId == null || it.childId == kid) }.map { it.toDto() }
    }

    @PostMapping("/admin/school-calendar/import")
    fun import(@RequestParam year: Int): org.springframework.http.ResponseEntity<Map<String, Any>> = try {
        val result = calendar.importBavarian(year)
        org.springframework.http.ResponseEntity.ok(mapOf("ok" to true, "imported" to result.imported, "alreadyPresent" to result.alreadyPresent))
    } catch (_: IllegalArgumentException) {
        org.springframework.http.ResponseEntity.badRequest().body(mapOf("ok" to false, "code" to "INVALID_YEAR"))
    } catch (_: de.schoolagent.calendar.HolidayImportUnavailable) {
        org.springframework.http.ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(mapOf("ok" to false, "code" to "HOLIDAYS_UNAVAILABLE"))
    }

    @PostMapping("/admin/school-calendar")
    fun create(@RequestBody request: EntryRequest): CalendarEntryDto {
        val entry = repo.save(
            SchoolCalendarEntry(
                childId = request.childId,
                name = request.name,
                startDate = request.startDate,
                endDate = request.endDate,
                entryType = CalendarEntryType.valueOf(request.entryType.uppercase()),
                createdAt = java.time.Instant.now(),
            ),
        )
        return entry.toDto()
    }

    @DeleteMapping("/admin/school-calendar/{id}")
    fun delete(@PathVariable id: UUID) {
        repo.deleteById(id)
    }

    data class EntryRequest(
        val childId: UUID? = null,
        val name: String,
        val startDate: LocalDate,
        val endDate: LocalDate,
        val entryType: String = "HOLIDAY",
    )

    private fun SchoolCalendarEntry.toDto() = CalendarEntryDto(id, childId, name, startDate, endDate, entryType.name)
}

@RestController
@RequestMapping("/api/actions")
class AcknowledgmentController(
    private val actions: de.schoolagent.domain.repo.ParentActionRepository,
    private val inbox: de.schoolagent.domain.service.FamilyInboxService,
    private val ackRepo: ActionAcknowledgmentRepository,
    private val users: AppUserRepository,
    private val clock: Clock,
) {

    data class BatchRequest(val actionIds: List<UUID>)

    @PostMapping("/acknowledge-batch")
    fun acknowledgeBatch(@RequestBody request: BatchRequest, @AuthenticationPrincipal user: UserDetails) {
        val account = users.findByName(user.username) ?: return
        for (actionId in request.actionIds) {
            val action = actions.findById(actionId).orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND) }
            inbox.accessible(action.caseId, user)
            if (!ackRepo.existsByActionIdAndUserId(actionId, account.id)) {
                ackRepo.save(
                    ActionAcknowledgment(
                        actionId = actionId,
                        userId = account.id,
                        acknowledgedAt = clock.instant(),
                    ),
                )
            }
        }
    }

    @GetMapping("/{id}/acknowledgments")
    fun acknowledgments(@PathVariable id: UUID, @AuthenticationPrincipal user: UserDetails): List<AcknowledgmentDto> {
        val action = actions.findById(id).orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND) }
        inbox.accessible(action.caseId, user)
        return ackRepo.findByActionId(id).mapNotNull { ack ->
            users.findById(ack.userId).orElse(null)?.let { u ->
                AcknowledgmentDto(u.name, u.displayName, ack.acknowledgedAt)
            }
        }
    }
}

@RestController
@RequestMapping("/api/account")
class UserPreferencesController(
    private val users: AppUserRepository,
    private val clock: Clock,
) {

    data class PreferencesRequest(
        val briefingEnabled: Boolean? = null,
        val briefingTime: String? = null,
        val weeklyEnabled: Boolean? = null,
        val weeklyTime: String? = null,
        val instantEnabled: Boolean? = null,
    )

    @PutMapping("/preferences")
    fun update(@RequestBody request: PreferencesRequest, @AuthenticationPrincipal user: UserDetails) {
        val account = users.findByName(user.username)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "user not found")
        request.briefingEnabled?.let { account.briefingEnabled = it }
        request.weeklyEnabled?.let { account.weeklyEnabled = it }
        request.instantEnabled?.let { account.instantEnabled = it }
        request.briefingTime?.let {
            if (!it.matches(Regex("^([01]?\\d|2[0-3]):[0-5]\\d$"))) {
                throw ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid time format (HH:mm)")
            }
            account.briefingTime = java.time.LocalTime.parse(it, java.time.format.DateTimeFormatter.ofPattern("H:mm")).toString()
        }
        request.weeklyTime?.let {
            if (!it.matches(Regex("^([01]?\\d|2[0-3]):[0-5]\\d$"))) {
                throw ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid time format (HH:mm)")
            }
            account.weeklyTime = java.time.LocalTime.parse(it, java.time.format.DateTimeFormatter.ofPattern("H:mm")).toString()
        }
        account.updatedAt = clock.instant()
        users.save(account)
    }
}
