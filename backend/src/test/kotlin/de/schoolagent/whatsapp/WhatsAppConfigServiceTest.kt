package de.schoolagent.whatsapp

import de.schoolagent.domain.model.Setting
import de.schoolagent.domain.repo.SettingRepository
import de.schoolagent.security.SecretVault
import io.mockk.*
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Clock
import java.util.Optional
import java.util.UUID

class WhatsAppConfigServiceTest {
    @Test
    fun `both children and inbound replies use the family connection even when legacy child secrets exist`() {
        val settings = mockk<SettingRepository>()
        val vault = mockk<SecretVault>()
        every { settings.findById(any()) } answers { Optional.of(Setting(key = firstArg(), valueJson = if (firstArg<String>() == "whatsapp.phone-number-id") "family-number" else "https://example.test")) }
        every { vault.read(any()) } answers { if (firstArg<String>() == "whatsapp.token") "family-token" else "family-secret" }
        val service = WhatsAppConfigService(settings, vault, mockk(), mockk(), Clock.systemUTC(), "https://school.example.test")
        val jamie = UUID.randomUUID()
        val taylor = UUID.randomUUID()
        assertThat(service.config(jamie)).isEqualTo(service.config(taylor)).isEqualTo(service.config())
        assertThat(service.withChild(taylor) { service.config().token }).isEqualTo("family-token")
        assertThat(service.childIdForPhoneNumberId("family-number")).isNull()
        assertThat(service.matchesVerifyToken("family-secret")).isTrue()
        verify(exactly = 0) { vault.read(match { it.contains(jamie.toString()) || it.contains(taylor.toString()) }) }
    }

    @Test
    fun `legacy kid-scoped saves update the single family configuration`() {
        val settings = mockk<SettingRepository>(relaxed = true)
        every { settings.save(any<Setting>()) } answers { firstArg() }
        val vault = mockk<SecretVault>(relaxed = true)
        val service = WhatsAppConfigService(settings, vault, mockk(), mockk(), Clock.systemUTC(), "https://school.example.test")
        service.save(UUID.randomUUID(), null, "family", null, "new-token", null, null, null, null)
        verify { vault.store("whatsapp.token", "new-token") }
        verify { settings.save(match { it.key == "whatsapp.phone-number-id" && it.valueJson == "family" }) }
    }
}
