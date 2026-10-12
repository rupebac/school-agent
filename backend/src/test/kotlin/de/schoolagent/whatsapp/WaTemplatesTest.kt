package de.schoolagent.whatsapp

import de.schoolagent.i18n.UserLanguage
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class WaTemplatesTest {
    private val home = "https://school.example.org"
    @Test fun `layouts use native buttons with separate action and information headings in both languages`() {
        val catalog = WaTemplates.catalog(home)
        assertThat(catalog.map { it.language }.toSet()).containsExactlyInAnyOrder("en_US", "es")
        assertThat(catalog.map { it.name to it.language }.distinct()).hasSize(catalog.size)
        catalog.forEach { spec ->
            assertThat(spec.header.length).isLessThanOrEqualTo(60)
            assertThat(spec.body).doesNotContain("Due: none", "{{0}}")
            val slots = Regex("\\{\\{(\\d+)}}").findAll(spec.body).map { it.groupValues[1].toInt() }.toList()
            assertThat(slots).containsExactlyElementsOf((1..spec.examples.size).toList())
            if (!spec.name.startsWith(WaTemplates.TEST)) assertThat(spec.url).isEqualTo("$home/{{1}}")
        }
        val info = WaTemplates.messages(WaTemplates.Kind.INFO, UserLanguage.EN, "Taylor", "Class letter", "New timetable.", emptyList(), "cases/example").single()
        assertThat(WaTemplates.render(info, home)).contains("School information for Taylor", "New timetable.", "Open letter:").doesNotContain("To do", "Due:")
    }
    @Test fun `every task survives paging and long unicode splits without removing dates amounts or links`() {
        val rows = (1..9).map { "$it · Prepare €17.20 on 5 Oct · " + "📚 school supplies ".repeat(30) + "https://school.example.org/cases/$it" }
        val messages = WaTemplates.messages(WaTemplates.Kind.ACTION, UserLanguage.EN, "Jamie", "Supplies", "A school letter.", rows, "cases/letter")
        val chunks = messages.flatMap { it.params.drop(1) }
        rows.forEach { row -> assertThat(chunks.joinToString(" ").replace("↳ Continued: ", "")).contains(WaTemplates.clean(row)) }
        messages.forEach { m ->
            assertThat(m.params).allSatisfy { assertThat(it).doesNotContain("\n", "\t"); assertThat(it.last().isHighSurrogate()).isFalse() }
            assertThat(m.params[0]).contains("/")
            assertThat(WaTemplates.render(m, home)).contains("$home/cases/letter")
        }
    }
    @Test fun `five plan entries fit in one message with the requested child heading and no boilerplate`() {
        val rows = (1..5).map { "Mon $it Oct · Bring the reading book · $home/cases/00000000-0000-0000-0000-00000000000$it" }
        val weekly = WaTemplates.messages(WaTemplates.Kind.PLAN, UserLanguage.EN, "Taylor", "5–11 Oct", "Ignored boilerplate", rows, "calendar?kid=taylor", weekly = true).single()
        assertThat(weekly.header).isEqualTo("Next week for Taylor")
        assertThat(weekly.params.drop(1)).containsExactlyElementsOf(rows)
        assertThat(WaTemplates.render(weekly, home)).startsWith("Next week for Taylor").doesNotContain("Here is", "grouped by date", "This reminder uses the school matters", "Ignored")
        assertThat(WaTemplates.messages(WaTemplates.Kind.PLAN, UserLanguage.EN, "Jamie", "Mon 5 Oct", "", rows, "calendar").single().header).isEqualTo("Tomorrow for Jamie")
        assertThat(WaTemplates.body(weekly, home).length).isLessThanOrEqualTo(1024)
    }
    @Test fun `one letter keeps its event deadline and full explained context in one approved entry`() {
        val title = "ADHD (attention deficit hyperactivity disorder) parents' evening"
        val context = "A psychologist discusses practical ways to support children at home, followed by questions from parents. Places are limited; registration is available by phone or email."
        val m = WaTemplates.messages(WaTemplates.Kind.ACTION, UserLanguage.EN, "Taylor", title, context,
            listOf("On 16.10.2026, 18:00 · Family centre", "By 13.10.2026: Register by phone or email"), "cases/letter").single()
        assertThat(m.name).isEqualTo(WaTemplates.name(WaTemplates.Kind.ACTION, 1))
        assertThat(m.params).containsExactly(title,
            "${context.removeSuffix(".")}; On 16.10.2026, 18:00 · Family centre; By 13.10.2026: Register by phone or email")
        assertThat(WaTemplates.body(m, home).length).isLessThanOrEqualTo(1024)
    }
    @Test fun `an instant letter explains an acronym only once even when its saved standalone reminder explains it`() {
        val meaning = "attention deficit hyperactivity disorder"
        val m = WaTemplates.messages(WaTemplates.Kind.ACTION, UserLanguage.EN, "Taylor", "ADHD ($meaning) evening",
            "Understanding ADHD and supporting children.", listOf("On 16.10.2026, 18:00", "By 13.10.2026: Register for the ADHD ($meaning) evening"),
            "cases/letter", abbreviations = mapOf("ADHD" to meaning)).single()
        val rendered = WaTemplates.render(m, home)
        assertThat(Regex(Regex.escape(meaning)).findAll(rendered).count()).isEqualTo(1)
        assertThat(m.params.last()).contains("Understanding ADHD and supporting children; On 16.10.2026, 18:00; By 13.10.2026: Register for the ADHD evening")
    }
    @Test fun `maximum parameter lengths keep each body below Meta rich template limit`() {
        UserLanguage.entries.forEach { lang -> WaTemplates.Kind.entries.forEach { kind ->
            val m = WaTemplates.messages(kind, lang, "K".repeat(40), "T".repeat(100), "", List(5) { "$it" + "R".repeat(139) }, "cases/example").single()
            val spec = WaTemplates.catalog(home).first { it.name == m.name && it.language == m.language }
            val body = Regex("\\{\\{(\\d+)}}").replace(spec.body) { m.params[it.groupValues[1].toInt()-1] }
            assertThat(body.length).isLessThanOrEqualTo(1024)
        } }
    }
}
