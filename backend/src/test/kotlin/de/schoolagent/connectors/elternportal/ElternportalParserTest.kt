package de.schoolagent.connectors.elternportal

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ElternportalParserTest {

    // Structure captured from a real eltern-portal.org brief list (redacted)
    private val page = """
        <html><body><table>
        <tr><td colspan='2'>#17 Empfang noch nicht bestätigt.</td></tr>
        <tr>
          <td>
            <a href='aktuelles/get_file/?repo=57930&csrf=dfda36e718841eb1'>Schwimmplan 26_27 (1. Hj.).pdf</a>
          </td>
          <td>Schwimmplan 2026/2027 23.09.2026, 14:31 Schwimmplan 26_27 (1. Hj.).pdf Klasse/n: 5A Liebe Eltern, sehr geehrte Erziehungsberechtigte, in der Anlage finden Sie die …</td>
        </tr>
        <tr><td colspan='2'>#16 Empfang bestätigt.</td></tr>
        <tr>
          <td>
            <a href='aktuelles/get_file/?repo=57923&csrf=08a78f0a9007544e'>Elternbrief_InformationenSchuPsy_2026-27.pdf</a>
          </td>
          <td>Schuljahresbeginn in der schulpsychologischen Beratung 23.09.2026, 11:00 Elternbrief_InformationenSchuPsy_2026-27.pdf Klasse/n: 5A Liebe Eltern, liebe Erziehung …</td>
        </tr>
        <tr><td>navigation row without repo link</td></tr>
        </table></body></html>
    """.trimIndent()

    @Test
    fun `parses briefs with stable repo ids, numbers, dates and pdf names`() {
        val briefs = ElternportalParser().parseBriefs(page)

        assertThat(briefs).hasSize(2)
        val first = briefs[0]
        assertThat(first.repoId).isEqualTo("57930")
        assertThat(first.number).isEqualTo(17)
        assertThat(first.csrf).isEqualTo("dfda36e718841eb1")
        assertThat(first.title).contains("Schwimmplan 2026/2027")
        assertThat(first.dateText).isEqualTo("23.09.2026, 14:31")
        assertThat(first.pdfName).contains("Schwimmplan 26_27")
        assertThat(first.classes).contains("5A")
        assertThat(briefs[1].repoId).isEqualTo("57923")
        assertThat(briefs[1].number).isEqualTo(16)
    }
}
