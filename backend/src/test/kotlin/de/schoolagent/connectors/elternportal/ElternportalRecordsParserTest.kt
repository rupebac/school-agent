package de.schoolagent.connectors.elternportal

import de.schoolagent.connectors.school.*
import org.assertj.core.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.LocalDate

class ElternportalRecordsParserTest {
    private val parser = ElternportalRecordsParser()
    private val student = SchoolStudent("42", "Jamie Example", "7C")
    private fun page(content: String, selected: String = "42", group: String = "7C") = """
        <h2 id="schule">Example School</h2>
        <select onchange="set_child(this.value)"><option value="42" ${if (selected == "42") "selected" else ""}>Jamie Example ($group)</option>
        <option value="43" ${if (selected == "43") "selected" else ""}>Pat Example (10B)</option></select>$content
    """
    private fun examPage(rows: String, group: String = "7C", selected: String = "42") = page("""
        <a href="service/termine/liste/schulaufgaben" class="active">Schulaufgabenplan ($group)</a>
        <a href="service/termine/liste/allgemein">Allgemeine Termine</a>
        <table class="termine-table">$rows</table>
    """, selected)
    private val row = "<tr><td>20.11.2026</td><td></td><td>Schulaufgabe in Mathematik (ABC)</td></tr>"

    @Test fun `matches arbitrary account children by configured name and class without assuming the first option`() {
        val identity = parser.identity(page(""))
        assertThat(parser.matchingStudent(identity, SchoolStudentScope("Pat", "10b", null)).id).isEqualTo("43")
        assertThat(parser.matchingStudent(identity, SchoolStudentScope("Jamie", "7c", null))).isEqualTo(student)
        assertThat(parser.matchingStudent(identity, SchoolStudentScope("Jamie", "10B", null))).isEqualTo(student)
        assertThatThrownBy { parser.matchingStudent(identity, SchoolStudentScope("Someone else", "7C", null)) }.isInstanceOf(SchoolConnectionException::class.java)
    }
    @Test fun `rejects ambiguous names and child or class mismatches`() {
        val identity = SchoolIdentity(null, null, listOf(student, student.copy(id = "99")))
        assertThatThrownBy { parser.matchingStudent(identity, SchoolStudentScope("Jamie", "7C", null)) }.isInstanceOf(SchoolConnectionException::class.java)
        assertThatThrownBy { parser.exams(examPage(row, "10B"), student, "url") }.isInstanceOf(SchoolConnectionException::class.java)
        assertThatThrownBy { parser.exams(examPage(row, selected = "43"), student, "url") }.isInstanceOf(SchoolConnectionException::class.java)
    }
    @Test fun `imports only the dedicated class table and identities survive row insertions and reordering`() {
        val html = examPage("<tr><td colspan='3'><h4>2026</h4></td></tr>$row") + "<table><tr><td>12.11.2026</td><td></td><td>Other class exam</td></tr></table>"
        val feed = parser.exams(html, student, "https://portal.example/service/termine/liste/schulaufgaben")
        assertThat(feed.records).hasSize(1)
        assertThat(feed.complete).isTrue()
        assertThat(feed.records.single().date).isEqualTo(LocalDate.of(2026, 11, 20))
        assertThat(feed.records.single().kind).isEqualTo(SchoolRecordKind.EXAM)
        val secondRow = row.replace("20.11", "24.11")
        val second = parser.exams(examPage(secondRow + row), student, "https://portal.example/service/termine/liste/schulaufgaben")
        assertThat(second.records.last().id).isEqualTo(feed.records.single().id)
    }
    @Test fun `real empty sections return zero records and no placeholder events`() {
        val report = page("""
            <div class='row'><h3>Vergessene Hausaufgaben</h3></div>
            <div class='row'><div class='col-md-4'></div><div class='col-md-6'>Es liegen keine Einträge zu vergessenen Hausaufgaben vor.</div></div>
            <div class='row'><h3>Vergessene Materialien</h3></div>
            <div class='row'><div class='col-md-4'></div><div class='col-md-6'>Es liegen keine Einträge zu vergessenen Materialien vor.</div></div>
        """)
        assertThat(parser.forgotten(report, student, "url")).allMatch { it.status == SchoolFeedStatus.AVAILABLE && it.records.isEmpty() }
        val payment = page("<table><thead><tr><th>Offene Zahlungsaufforderungen</th></tr><tr><td>Keine Einträge</td></tr></thead><tbody></tbody></table>")
        assertThat(parser.payments(payment, student, "url").records).isEmpty()
        assertThat(parser.exams(examPage("<tr><td colspan='3'>Keine Einträge</td></tr>"), student, "url").records).isEmpty()
    }
    @Test fun `dated forgotten reports and payment facts remain distinct with no invented deadlines`() {
        val report = page("""
            <div class='row'><h3>Vergessene Hausaufgaben</h3></div>
            <div class='row' data-id='80'><div class='col-md-4'>09.10.2026</div><div class='col-md-6'>Mathematik: Übungsblatt vergessen</div></div>
            <div class='row'><h3>Vergessene Materialien</h3></div>
            <div class='row' data-id='81'><div class='col-md-4'>08.10.2026</div><div class='col-md-6'>Deutsch: Heft vergessen</div></div>
        """)
        val feeds = parser.forgotten(report, student, "url")
        assertThat(feeds.map { it.records.single().kind }).containsExactly(SchoolRecordKind.FORGOTTEN_HOMEWORK, SchoolRecordKind.FORGOTTEN_MATERIAL)
        assertThat(feeds.map { it.records.single().dueDate }).containsOnlyNulls()
        val payment = page("""<table><thead><tr><th>Offene Zahlungsaufforderungen</th></tr></thead>
            <tbody><tr data-id='90'><td><strong>Kopiergeld</strong></td><td>17,20 €</td><td>Fällig am 15.10.2026</td></tr></tbody></table>""")
        assertThat(parser.payments(payment, student, "url").records.single().dueDate).isEqualTo(LocalDate.of(2026, 10, 15))
    }
    @Test fun `exam headers are accepted but a partial empty table is not a withdrawal snapshot`() {
        val header = "<tr><th>Datum</th><th>Zeit</th><th>Fach</th></tr>"
        assertThat(parser.exams(examPage(header + row), student, "url").records).hasSize(1)
        assertThatThrownBy { parser.exams(examPage(header), student, "url") }.isInstanceOf(SchoolConnectionException::class.java)
        assertThat(parser.matchingStudent(parser.identity(page("", group = "8C")), SchoolStudentScope("Jamie", "7C", "42")).id).isEqualTo("42")
        assertThatThrownBy { parser.matchingStudent(parser.identity(page("")), SchoolStudentScope("Pat", "7C", "42")) }.isInstanceOf(SchoolConnectionException::class.java)
    }

    @Test fun `payment rows without stable native IDs fail to avoid duplicated bills when amounts change`() {
        val payment = page("""<table><thead><tr><th>Offene Zahlungsaufforderungen</th></tr></thead>
            <tbody><tr><td>Kopiergeld</td><td>17,20 €</td></tr></tbody></table>""")
        assertThatThrownBy { parser.payments(payment, student, "url") }.isInstanceOf(SchoolConnectionException::class.java)
    }
    @Test fun `unknown nonempty layouts and login responses fail rather than pretending to be empty`() {
        assertThatThrownBy { parser.exams("<form>Login</form>", student, "url") }.isInstanceOf(SchoolConnectionException::class.java)
        assertThatThrownBy { parser.exams(examPage("<tr><td>No recognizable date</td><td></td><td>Exam</td></tr>"), student, "url") }.isInstanceOf(SchoolConnectionException::class.java)
        assertThatThrownBy { parser.payments(page("<table><th>Offene Zahlungsaufforderungen</th></table>"), student, "url") }.isInstanceOf(SchoolConnectionException::class.java)
    }
}
