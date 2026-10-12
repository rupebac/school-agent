package de.schoolagent.connectors.google

import de.schoolagent.connectors.calendar.CalendarAdminService
import de.schoolagent.connectors.calendar.CalendarInfoDto
import de.schoolagent.domain.model.Setting
import de.schoolagent.domain.repo.SettingRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Optional

class GoogleCalendarSetupServiceTest {

    private val admin = mockk<CalendarAdminService>(relaxed = true)
    private val oauth = mockk<GoogleOAuthService>()
    private val settings = mockk<SettingRepository>(relaxed = true)
    private val clock = Clock.fixed(Instant.parse("2026-09-23T12:00:00Z"), ZoneOffset.UTC)

    private val props = GoogleProperties().apply {
        calendarId = "primary"
        schoolCalendarName = "Jamie – Schule"
    }

    private val stored = mutableMapOf<String, String>()
    private val shared = mutableListOf<Pair<String, String>>()

    private lateinit var service: GoogleCalendarSetupService

    @BeforeEach
    fun setUp() {
        stored.clear()
        shared.clear()
        every { settings.findById(any()) } answers {
            val k: String = firstArg()
            stored[k]?.let { Optional.of(Setting(key = k, valueJson = it)) } ?: Optional.empty<Setting>()
        }
        every { settings.save(any<Setting>()) } answers {
            val s = firstArg<Setting>()
            stored[s.key] = s.valueJson ?: ""
            s
        }
        every { settings.deleteById(any()) } answers { stored.remove(firstArg<String>()) }
        every { admin.shareWithUser(any(), any(), any()) } answers {
            val calId: String = firstArg()
            val email: String = secondArg()
            shared.add(calId to email)
            Unit
        }
        val kidsMock = io.mockk.mockk<de.schoolagent.domain.repo.ChildRepository>(relaxed = true)
        io.mockk.every { kidsMock.findById(any()) } returns java.util.Optional.empty()
        service = GoogleCalendarSetupService(admin, oauth, props, settings, kidsMock, clock)
    }

    @Test
    fun `resolveCalendarId prefers the stored choice over the env property`() {
        stored[GoogleCalendarSetupService.SETTING_KEY] = "cal-abc@group.calendar.google.com"
        assertThat(service.resolveCalendarId()).isEqualTo("cal-abc@group.calendar.google.com")

        assertThat(service.resolveCalendarId()).isEqualTo("cal-abc@group.calendar.google.com")
        stored.clear()
        assertThat(service.resolveCalendarId()).isEqualTo("primary")
    }

    @Test
    fun `status when not connected reports the expected name and no calendars`() {
        every { oauth.isConfigured() } returns false

        val status = service.status()

        assertThat(status.connected).isFalse()
        assertThat(status.expectedName).isEqualTo("Jamie – Schule")
        assertThat(status.schoolCalendarFound).isNull()
        assertThat(status.calendars).isEmpty()
    }

    @Test
    fun `status detects an existing school calendar by name`() {
        every { oauth.isConfigured() } returns true
        every { admin.listCalendars() } returns listOf(
            CalendarInfoDto("jamie@cal", "Jamie – Schule", "Europe/Berlin"),
            CalendarInfoDto("primary", "Jamie Perez", "Europe/Berlin"),
        )

        val status = service.status()

        assertThat(status.connected).isTrue()
        assertThat(status.schoolCalendarFound?.id).isEqualTo("jamie@cal")
        assertThat(status.calendars).hasSize(2)
    }

    @Test
    fun `ensure creates the calendar when missing, shares with parents and persists the id`() {
        every { oauth.isConfigured() } returns true
        every { admin.listCalendars() } returns listOf(CalendarInfoDto("primary", "Jamie Perez", null))
        every { admin.createCalendar("Jamie – Schule", "Europe/Berlin") } returns
            CalendarInfoDto("new-cal-1", "Jamie – Schule", "Europe/Berlin")

        val created = service.ensureSchoolCalendar(null, listOf("morgan@family.example", " casey@family.example "))

        assertThat(created.id).isEqualTo("new-cal-1")
        assertThat(stored[GoogleCalendarSetupService.SETTING_KEY]).isEqualTo("new-cal-1")
        assertThat(shared).containsExactly(
            "new-cal-1" to "morgan@family.example",
            "new-cal-1" to "casey@family.example",
        )
    }

    @Test
    fun `ensure adopts an existing calendar instead of duplicating it`() {
        every { oauth.isConfigured() } returns true
        every { admin.listCalendars() } returns listOf(CalendarInfoDto("existing-1", "Jamie – Schule", null))

        val adopted = service.ensureSchoolCalendar(null, emptyList())

        assertThat(adopted.id).isEqualTo("existing-1")
        verify(exactly = 0) { admin.createCalendar(any(), any()) }
        assertThat(stored[GoogleCalendarSetupService.SETTING_KEY]).isEqualTo("existing-1")
    }

    @Test
    fun `ensure without a Google connection is rejected`() {
        every { oauth.isConfigured() } returns false
        assertThatThrownBy { service.ensureSchoolCalendar(null, emptyList()) }
            .isInstanceOf(GoogleNotConnectedException::class.java)
    }
}
