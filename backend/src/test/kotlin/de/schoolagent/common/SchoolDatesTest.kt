package de.schoolagent.common

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalTime

class SchoolDatesTest {

    @Test
    fun `parses German and ISO dates`() {
        assertThat(SchoolDates.date("29.09.2026")).isEqualTo(LocalDate.of(2026, 9, 29))
        assertThat(SchoolDates.date("2026-10-15")).isEqualTo(LocalDate.of(2026, 10, 15))
        assertThat(SchoolDates.date("null")).isNull()
        assertThat(SchoolDates.date("")).isNull()
    }

    @Test
    fun `parses clock times including German Uhr`() {
        assertThat(SchoolDates.time("18:00")).isEqualTo(LocalTime.of(18, 0))
        assertThat(SchoolDates.time("18.30")).isEqualTo(LocalTime.of(18, 30))
        assertThat(SchoolDates.time("8 Uhr")).isEqualTo(LocalTime.of(8, 0))
        assertThat(SchoolDates.time("18:00 Uhr")).isEqualTo(LocalTime.of(18, 0))
        assertThat(SchoolDates.time(null)).isNull()
    }
}
