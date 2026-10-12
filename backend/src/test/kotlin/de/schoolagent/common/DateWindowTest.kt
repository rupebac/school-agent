package de.schoolagent.common

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.LocalDate

class DateWindowTest {

    private val today = LocalDate.of(2026, 9, 24)

    @Test
    fun `after january and before september`() {
        val w = DateWindowParser.parse("before 1st september and after 1january", today)!!
        assertThat(w.fromInclusive).isEqualTo(LocalDate.of(2026, 1, 1))
        assertThat(w.toExclusive).isEqualTo(LocalDate.of(2026, 9, 1))
        assertThat(w.contains(LocalDate.of(2026, 8, 31))).isTrue()
        assertThat(w.contains(LocalDate.of(2026, 9, 1))).isFalse()
        assertThat(w.contains(LocalDate.of(2025, 12, 31))).isFalse()
    }

    @Test
    fun `in september is that calendar month`() {
        val w = DateWindowParser.parse("anything in September", today)!!
        assertThat(w.fromInclusive).isEqualTo(LocalDate.of(2026, 9, 1))
        assertThat(w.toExclusive).isEqualTo(LocalDate.of(2026, 10, 1))
    }

    @Test
    fun `between two named dates`() {
        val w = DateWindowParser.parse("events between 1 January and 1 March", today)!!
        assertThat(w.fromInclusive).isEqualTo(LocalDate.of(2026, 1, 1))
        assertThat(w.toExclusive).isEqualTo(LocalDate.of(2026, 3, 2))
    }

    @Test
    fun `spanish despues and antes`() {
        val w = DateWindowParser.parse("despues del 1 de enero y antes del 1 de septiembre", today)!!
        assertThat(w.fromInclusive).isEqualTo(LocalDate.of(2026, 1, 1))
        assertThat(w.toExclusive).isEqualTo(LocalDate.of(2026, 9, 1))
    }

    @Test
    fun `this week is monday to monday`() {
        val w = DateWindowParser.parse("what's on this week?", today)!!
        assertThat(w.fromInclusive).isEqualTo(LocalDate.of(2026, 9, 21))
        assertThat(w.toExclusive).isEqualTo(LocalDate.of(2026, 9, 28))
    }

    @Test
    fun `no window when there are no dates`() {
        assertThat(DateWindowParser.parse("when is the Elternabend?", today)).isNull()
    }
}
