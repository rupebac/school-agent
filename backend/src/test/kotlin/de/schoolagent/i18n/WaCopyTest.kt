package de.schoolagent.i18n

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class WaCopyTest {

    @Test
    fun `info letter says there is nothing to do`() {
        val text = WaCopy.decision(
            lang = UserLanguage.EN,
            score = 1,
            kid = "Jamie",
            title = "Autumn menu",
            deadline = "01.10.2026",
            summary = "Pizza on Friday.",
            actionTitles = emptyList(),
            url = "http://x/cases/1",
            infoOnly = true,
        )
        assertThat(text).contains("Info only · no action needed.")
        assertThat(text).contains("Autumn menu")
        assertThat(text).contains("Pizza on Friday.")
        assertThat(text).doesNotContain("You need to")
        assertThat(text).doesNotContain("❗")
    }

    @Test
    fun `new letter includes what when and the task`() {
        val text = WaCopy.decision(
            lang = UserLanguage.EN,
            score = 3,
            kid = "Jamie",
            title = "Forstlauf 2026",
            deadline = "29.09.2026",
            summary = "Register and pay €10. Race on Saturday in Anzing.",
            actionTitles = listOf("Register and pay €10 for Forstlauf via PE teacher"),
            url = "http://x/cases/2",
            eventWhen = "03.10.2026",
            eventWhere = "Anzing",
        )
        assertThat(text).contains("*Jamie · Forstlauf 2026*")
        assertThat(text).contains("Forstlauf 2026")
        assertThat(text).contains("Register and pay €10. Race on Saturday in Anzing.")
        assertThat(text).contains("When: 03.10.2026")
        assertThat(text).contains("Due: 29.09.2026")
        assertThat(text).contains("Where: Anzing")
        assertThat(text).contains("To do / prepare:")
        assertThat(text).contains("• Register and pay €10 for Forstlauf via PE teacher")
        assertThat(text).contains("Open in School Agent:\n↗ http://x/cases/2")
        assertThat(text).doesNotContain("❗")
    }

    @Test
    fun `score 3 with no actions is flagged as info`() {
        val text = WaCopy.decision(
            lang = UserLanguage.EN,
            score = 3,
            kid = "Jamie",
            title = "Book borrowing",
            deadline = null,
            summary = "Stamp the loan sheet.",
            actionTitles = emptyList(),
            url = "http://x/cases/4",
            infoOnly = true,
        )
        assertThat(text).contains("Info only · no action needed.")
        assertThat(text).contains("Book borrowing")
        assertThat(text).doesNotContain("You need to")
    }

    @Test
    fun `score 4-5 is a full written note`() {
        val text = WaCopy.decision(
            lang = UserLanguage.ES,
            score = 5,
            kid = "Jamie",
            title = "Schullandheim",
            deadline = "29.09.2026",
            summary = "Confirm by Monday.",
            actionTitles = listOf("Confirm participation"),
            url = "http://x/cases/3",
        )
        assertThat(text).startsWith("*Jamie · Schullandheim*")
        assertThat(text).contains("Schullandheim")
        assertThat(text).contains("Confirm by Monday.")
        assertThat(text).contains("Fecha límite: 29.09.2026")
        assertThat(text).contains("Preparar / hacer:")
        assertThat(text).contains("• Confirm participation")
        assertThat(text).contains("Abrir en School Agent:\n↗ http://x/cases/3")
    }
    @Test
    fun `same date and repeated task are shown only once`() {
        val text = WaCopy.decision(UserLanguage.EN, 4, "Jamie", "School trip", "01.10.2026",
            "Return form", listOf("Return form", " return   form "), "https://school.example.org/cases/1",
            eventWhen = "01.10.2026")
        assertThat(Regex("01.10.2026").findAll(text).count()).isEqualTo(1)
        assertThat(Regex("Return form").findAll(text).count()).isEqualTo(1)
        assertThat(text).doesNotContain("\n\n\n")
        assertThat(text).contains("Due: 01.10.2026", "↗ https://school.example.org/cases/1")
    }

    @Test
    fun `info override hides stale task and deadline copy`() {
        val text = WaCopy.decision(UserLanguage.EN, 4, "Taylor", "Menu", "01.10.2026", null,
            listOf("Read menu"), "https://school.example.org/cases/1", infoOnly = true)
        assertThat(text).contains("Info only · no action needed.")
        assertThat(text).doesNotContain("Due:", "Read menu", "To do / prepare:", "\n\n\n")
    }

}
