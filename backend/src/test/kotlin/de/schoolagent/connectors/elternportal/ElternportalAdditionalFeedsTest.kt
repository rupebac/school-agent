package de.schoolagent.connectors.elternportal

import de.schoolagent.connectors.school.*
import org.assertj.core.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.LocalDate

class ElternportalAdditionalFeedsTest {
    private val parser = ElternportalAdditionalFeeds()
    private val student = SchoolStudent("7", "Synthetic Child", "8C")
    private fun page(content: String) = """<select onchange="set_child(this.value)">
        <option selected value="7">Synthetic Child (8C)</option></select>$content"""

    @Test fun `noticeboard preserves attachment identity without storing download tokens in its text`() {
        val html = page("""<h2>Schwarzes Brett</h2><div class="grid"><div class="grid-item"><div class="well">
            <p class="text-right">20.10.2026</p><h4>School concert</h4><p>Tickets open next week.</p>
            <a href="/aktuelles/get_file/?repo=11&amp;csrf=aabbcc"><img src="/includes/project/pdf.gif">Programme</a>
            </div></div></div>""")
        val item = parser.board(html, student, "https://school.example.test").single()
        assertThat(item.notice.kind).isEqualTo(SchoolNoticeKind.NOTICEBOARD)
        assertThat(item.attachments.single().repoId).isEqualTo("11")
        assertThat(item.html).contains("Tickets open next week.").doesNotContain("csrf", "aabbcc")
    }

    @Test fun `noticeboard image attachments retain their media type and are not rejected as invalid PDFs`() {
        val brief = PortalBrief("11", null, "Scanned school announcement", null, null, null, "aabbcc")
        val image = java.awt.image.BufferedImage(2, 2, java.awt.image.BufferedImage.TYPE_INT_RGB)
        for ((format, mime) in listOf("jpeg" to "image/jpeg", "png" to "image/png", "gif" to "image/gif")) {
            val bytes = java.io.ByteArrayOutputStream().also { javax.imageio.ImageIO.write(image, format, it) }.toByteArray()
            val part = parser.attachment(brief, bytes)
            assertThat(part.id).isEqualTo(brief.repoId)
            assertThat(part.mimeType).isEqualTo(mime)
            assertThat(part.bytes).isEqualTo(bytes)
        }
        assertThat(parser.attachment(brief, "%PDF-1.7 synthetic".toByteArray()).mimeType).isEqualTo("application/pdf")
        assertThatThrownBy { parser.attachment(brief, "<html>Login required</html>".toByteArray()) }
            .isInstanceOf(SchoolConnectionException::class.java)
    }

    @Test fun `classbook reads assignments from the requested day and uses only explicit deadlines`() {
        val html = page("""<h2>Klassenbuch</h2><input name="cur_date" value="20.10.2026">
            <a href="/service/klassenbuch?cur_date=19.10.2026">Previous day</a>
            <table class="table table-bordered"><thead><tr><th></th><th>Mathematics</th></tr></thead>
            <tbody><tr><td>Unterricht</td><td>Fractions</td></tr>
            <tr><td>Hausaufgabe</td><td>Workbook page 12. Abgabe: 23.10.2026</td></tr></tbody></table>""")
        val day = LocalDate.of(2026, 10, 20)
        val record = parser.homework(html, student, day, "https://school.example.test/classbook").records.single()
        assertThat(record.date).isEqualTo(day)
        assertThat(record.dueDate).isEqualTo(LocalDate.of(2026, 10, 23))
        assertThat(record.text).doesNotContain("Fractions")
        assertThat(day.format(parser.classbookDateFormat(html))).isEqualTo("20.10.2026")
        assertThatThrownBy { parser.homework(html, student, day.minusDays(1), "https://school.example.test/classbook") }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test fun `class substitutions keep the reported period and never invent clock times`() {
        val html = page("""<h2>Vertretungsplan (8C)</h2><div class="main_center">
            <div class="list bold full_width text_center">20.10.2026</div><table>
            <tr class="vp_plan_head"><td>Std.</td><td>Betrifft</td><td>Vertretung</td><td>Fach</td><td>Raum</td><td>Info</td></tr>
            <tr class="liste_grau"><td>2</td><td>XYZ</td><td>Entfall</td><td>Mathematics</td><td></td><td></td></tr>
            </table></div>""")
        val record = parser.timetable(html, student, "https://school.example.test/timetable").records.single()
        assertThat(record.timetable?.cancelled).isTrue()
        assertThat(record.time).isNull()
        assertThat(record.text).contains("Class timetable", "XYZ").doesNotContain("arrive", "leave")
        assertThatThrownBy { parser.timetable(html.replace("Vertretungsplan (8C)", "Vertretungsplan (9B)"),
            student, "https://school.example.test/timetable") }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test fun `an empty document or unknown table is a failure rather than an empty homework feed`() {
        assertThatThrownBy { parser.homework(page("<h2>Klassenbuch</h2><input name='cur_date' value='20.10.2026'>"),
            student, LocalDate.of(2026, 10, 20), "https://school.example.test/classbook") }.isInstanceOf(IllegalArgumentException::class.java)
    }
    @Test fun `plain staff substitutions are quiet but a replaced subject is explicit schedule evidence`() {
        fun read(subject: String) = parser.timetable(page("""<h2>Vertretungsplan (8C)</h2><div class="main_center">
            <div class="list bold full_width text_center">20.10.2026</div><table>
            <tr class="vp_plan_head"><td>Std.</td><td>Betrifft</td><td>Vertretung</td><td>Fach</td><td>Raum</td><td>Info</td></tr>
            <tr class="liste_grau"><td>3</td><td>AAA</td><td>BBB</td><td>$subject</td><td>12</td><td></td></tr>
            </table></div>"""), student, "https://school.example.test/timetable").records.single()
        assertThat(read("Mathematics").timetable?.scheduleChanged).isFalse()
        val changed = read("<s>Mathematics</s> English")
        assertThat(changed.timetable?.subject).isEqualTo("English")
        assertThat(changed.timetable?.scheduleChanged).isTrue()
    }

}
