package de.schoolagent

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.env.YamlPropertySourceLoader
import org.springframework.core.io.FileSystemResource

class ProductionConfigurationTest {
    @Test
    fun `production configuration loads without duplicate keys`() {
        // Integration tests use their own application.yml; validate the deployed file too.
        val sources = YamlPropertySourceLoader().load(
            "production", FileSystemResource("src/main/resources/application.yml")
        )
        assertThat(sources).isNotEmpty
        assertThat(sources.first().getProperty("schoolagent.google.gmail-query"))
            .isEqualTo("\${SCHOOLAGENT_GMAIL_QUERY:in:inbox}")
    }
}
