package de.schoolagent.common

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class TitleSimilarityTest {

    @Test
    fun `weltatlas survey paraphrases are the same obligation`() {
        assertThat(
            TitleSimilarity.similar(
                "Deadline for Diercke Weltatlas order survey",
                "Deadline: Elternportal survey for Diercke Weltatlas bulk order",
            ),
        ).isTrue()
        assertThat(
            TitleSimilarity.similar(
                "Answer binding survey on Diercke Weltatlas bulk order in Elternportal",
                "Answer the binding Elternportal survey on the Diercke Weltatlas bulk order (35.50 EUR)",
            ),
        ).isTrue()
        assertThat(
            TitleSimilarity.similar(
                "Payment for Diercke Weltatlas (€35.50) if ordered",
                "Payment for Diercke Weltatlas (35.50 EUR) per attached payment modalities",
            ),
        ).isTrue()
    }

    @Test
    fun `first-day-of-school paraphrases match, unrelated titles do not`() {
        assertThat(
            TitleSimilarity.similar(
                "First day of school 5th grade – assembly 08:20, lessons until 11:20",
                "First day of school: meet 08:20 in Aula, lessons until 11:20",
            ),
        ).isTrue()
        assertThat(
            TitleSimilarity.similar(
                "Autumn concert in the aula",
                "Diercke Weltatlas bulk order",
            ),
        ).isFalse()
    }
}
