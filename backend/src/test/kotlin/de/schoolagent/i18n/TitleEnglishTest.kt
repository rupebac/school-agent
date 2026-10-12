package de.schoolagent.i18n

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class TitleEnglishTest {

    @Test
    fun `letter headings look German, domain names do not`() {
        assertThat(looksGerman("Herbstkonzert in der Aula")).isTrue()
        assertThat(looksGerman("Elternabend Klasse 5a")).isTrue()
        assertThat(looksGerman("Anmeldung zum Ausflug")).isTrue()
        assertThat(looksGerman("Schullandheim")).isFalse()
        assertThat(looksGerman("Confirm participation")).isFalse()
        assertThat(looksGerman("Autumn concert")).isFalse()
        assertThat(looksSpanish("Debe traer zapatos el viernes")).isTrue()
        assertThat(alreadyInLanguage("Confirm participation by Friday.", UserLanguage.EN)).isTrue()
        assertThat(alreadyInLanguage("Jamie muss am Freitag Turnschuhe mitbringen.", UserLanguage.EN)).isFalse()
    }
}
