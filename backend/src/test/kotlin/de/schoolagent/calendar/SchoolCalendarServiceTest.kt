package de.schoolagent.calendar

import de.schoolagent.domain.model.CalendarEntryType
import de.schoolagent.domain.model.SchoolCalendarEntry
import de.schoolagent.domain.repo.SchoolCalendarEntryRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.web.client.RestTemplate
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

class SchoolCalendarServiceTest {

    private val repo = mockk<SchoolCalendarEntryRepository>()
    private val rest = mockk<RestTemplate>()
    private lateinit var service: SchoolCalendarService

    @BeforeEach
    fun setUp() {
        service = SchoolCalendarService(
            repo,
            rest,
            Clock.fixed(Instant.parse("2026-09-24T12:00:00Z"), ZoneOffset.UTC),
        )
    }

    @Test
    fun `empty calendar treats weekdays as school days and weekends as not`() {
        every { repo.findByStartDateLessThanEqualAndEndDateGreaterThanEqual(any(), any()) } returns emptyList()
        assertThat(service.isSchoolDay(LocalDate.of(2026, 9, 24))).isTrue() // Thursday
        assertThat(service.isSchoolDay(LocalDate.of(2026, 9, 26))).isFalse() // Saturday
        assertThat(service.isSchoolDay(LocalDate.of(2026, 9, 27))).isFalse() // Sunday
        assertThat(service.holidayName(LocalDate.of(2026, 9, 24))).isNull()
    }

    @Test
    fun `family-wide vacation covers the start and end dates`() {
        val autumn = LocalDate.of(2026, 10, 31)
        val entry = SchoolCalendarEntry(
            childId = null,
            name = "Herbstferien",
            startDate = LocalDate.of(2026, 10, 31),
            endDate = LocalDate.of(2026, 11, 6),
            entryType = CalendarEntryType.VACATION,
        )
        every { repo.findByStartDateLessThanEqualAndEndDateGreaterThanEqual(any(), any()) } answers {
            val d = firstArg<LocalDate>()
            if (!d.isBefore(LocalDate.of(2026, 10, 31)) && !d.isAfter(LocalDate.of(2026, 11, 6))) {
                listOf(entry)
            } else {
                emptyList()
            }
        }

        assertThat(service.isSchoolDay(autumn)).isFalse()
        assertThat(service.holidayName(autumn)).isEqualTo("Herbstferien")
        assertThat(service.isSchoolDay(LocalDate.of(2026, 11, 6))).isFalse()
        assertThat(service.isSchoolDay(LocalDate.of(2026, 11, 9))).isTrue() // Monday after
    }

    @Test
    fun `kid-specific holiday does not affect another child`() {
        val date = LocalDate.of(2026, 5, 14)
        val jamie = UUID.fromString("00000000-0000-0000-0000-0000000000aa")
        val other = UUID.fromString("00000000-0000-0000-0000-0000000000bb")
        val entry = SchoolCalendarEntry(
            childId = jamie,
            name = "Bußtag",
            startDate = date,
            endDate = date,
            entryType = CalendarEntryType.HOLIDAY,
        )
        every { repo.findByStartDateLessThanEqualAndEndDateGreaterThanEqual(date, date) } returns listOf(entry)

        assertThat(service.isSchoolDay(date, jamie)).isFalse()
        assertThat(service.isSchoolDay(date, other)).isTrue()
        assertThat(service.holidayName(date, other)).isNull()
    }

    @Test
    fun `schoolDaysBetween skips weekends and holidays`() {
        val from = LocalDate.of(2026, 10, 29) // Thursday
        val holiday = SchoolCalendarEntry(
            childId = null,
            name = "Reformationstag",
            startDate = LocalDate.of(2026, 10, 31),
            endDate = LocalDate.of(2026, 10, 31),
            entryType = CalendarEntryType.HOLIDAY,
        )
        every { repo.findByStartDateLessThanEqualAndEndDateGreaterThanEqual(any(), any()) } answers {
            val d = firstArg<LocalDate>()
            if (d == LocalDate.of(2026, 10, 31)) listOf(holiday) else emptyList()
        }
        // Thu 29, Fri 30, Sat 31 holiday/weekend, Sun 1, Mon 2
        assertThat(service.schoolDaysBetween(from, LocalDate.of(2026, 11, 2))).isEqualTo(3)
    }
    private val primary = "https://ferien-api.de/api/v1/holidays/BY/2027"
    private val fallback = "https://schulferien-api.de/api/v2/2027/BY"
    private val response = """[{"name":"synthetic-break","start":"2027-05-03T00:00Z","end":"2027-05-07T23:59Z","stateCode":"BY","year":2027}]"""

    @Test fun `rate limited provider falls back and repeated import stays idempotent with cooldown`() {
        every { rest.getForObject(primary, String::class.java) } throws org.springframework.web.client.HttpClientErrorException(
            org.springframework.http.HttpStatus.TOO_MANY_REQUESTS)
        every { rest.getForObject(fallback, String::class.java) } returns response
        var saved: SchoolCalendarEntry? = null
        every { repo.findByChildIdIsNullAndNameAndStartDateAndEndDate(any(), any(), any()) } answers { saved }
        every { repo.save(any<SchoolCalendarEntry>()) } answers { firstArg<SchoolCalendarEntry>().also { saved = it } }
        assertThat(service.importBavarian(2027)).isEqualTo(SchoolCalendarService.ImportResult(1, 0))
        assertThat(service.importBavarian(2027)).isEqualTo(SchoolCalendarService.ImportResult(0, 1))
        assertThat(saved!!.startDate).isEqualTo(LocalDate.of(2027, 5, 3))
        assertThat(saved!!.endDate).isEqualTo(LocalDate.of(2027, 5, 7))
        assertThat(saved!!.childId).isNull()
        verify(exactly = 1) { rest.getForObject(primary, String::class.java) }
        verify(exactly = 2) { rest.getForObject(fallback, String::class.java) }
    }
    @Test fun `malformed incomplete or wrong-state provider responses never partially persist`() {
        for (bad in listOf("[]", "{}", response.replace("BY", "BE"), response.replace("2027", "2028"),
            response.dropLast(1) + ",{}]")) {
            every { rest.getForObject(primary, String::class.java) } returns bad
            every { rest.getForObject(fallback, String::class.java) } returns bad
            assertThatThrownBy { service.importBavarian(2027) }.isInstanceOf(HolidayImportUnavailable::class.java)
        }
        verify(exactly = 0) { repo.save(any<SchoolCalendarEntry>()) }
    }
    @Test fun `invalid requested years never call external providers`() {
        assertThatThrownBy { service.importBavarian(9999) }.isInstanceOf(IllegalArgumentException::class.java)
        verify(exactly = 0) { rest.getForObject(any<String>(), String::class.java) }
    }

}
