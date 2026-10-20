package de.schoolagent.connectors

import de.schoolagent.connectors.school.SchoolText
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class SchoolTextTest {
    @Test fun `readable school text retains ordinary links without temporary download credentials`() {
        val text = SchoolText.fromHtml("""<p>Choose a date.</p><p><a href="https://forms.example.test/register?id=7">Register here</a></p>
            <a href="https://files.example.test/document?token=synthetic">Attachment</a><script>ignored()</script>""")
        assertThat(text).contains("Choose a date.\n", "Register here (https://forms.example.test/register?id=7)", "Attachment")
            .doesNotContain("token=", "synthetic", "ignored()")
    }
}
