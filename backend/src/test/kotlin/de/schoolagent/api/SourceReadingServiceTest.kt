package de.schoolagent.api

import de.schoolagent.ai.ModelRouter
import de.schoolagent.common.DedupKeys
import de.schoolagent.domain.enums.ModelRole
import de.schoolagent.domain.enums.SourceType
import de.schoolagent.domain.model.SchoolCase
import de.schoolagent.domain.model.Source
import de.schoolagent.domain.repo.SchoolCaseRepository
import de.schoolagent.domain.repo.SourceRepository
import de.schoolagent.domain.repo.SourceTranslationRepository
import de.schoolagent.i18n.UserLanguage
import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Instant
import java.util.Optional
import java.util.UUID

class SourceReadingServiceTest {

    private fun noTranslations(): SourceTranslationRepository {
        val repo = mockk<SourceTranslationRepository>()
        every { repo.findBySourceIdAndLanguageAndContentHash(any(), any(), any()) } returns null
        every { repo.save(any<de.schoolagent.domain.model.SourceTranslation>()) } answers { firstArg() }
        return repo
    }

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `extracts plaintext and skips translation when the model is unavailable`() {
        val documents = de.schoolagent.documents.FileSystemDocumentStore(tempDir.toString())
        val original = "Jamie muss am Freitag Turnschuhe mitbringen."
        val ref = documents.put(original.toByteArray())
        val caseId = UUID.randomUUID()
        val source = source(caseId, ref, "text/plain")

        val cases = mockk<SchoolCaseRepository>()
        val sources = mockk<SourceRepository>()
        every { cases.findById(caseId) } returns Optional.of(mockk<SchoolCase> { every { id } returns caseId })
        every { sources.findById(source.id) } returns Optional.of(source)
        val router = mockk<ModelRouter>()
        every { router.available(any()) } returns false

        val reading = SourceReadingService(cases, sources, noTranslations(), documents, router)
            .read(caseId, source.id, UserLanguage.EN)

        assertThat(reading.originalText).contains("Turnschuhe")
        assertThat(reading.translatedText).isEqualTo(reading.originalText)
        assertThat(reading.translated).isFalse()
        assertThat(reading.language).isEqualTo("en")
    }

    @Test
    fun `uses extraction model to translate into the viewer language`() {
        val documents = de.schoolagent.documents.FileSystemDocumentStore(tempDir.toString())
        val original = "Jamie muss am Freitag Turnschuhe mitbringen. Das gilt fuer die ganze Klasse."
        val ref = documents.put(original.toByteArray())
        val caseId = UUID.randomUUID()
        val source = source(caseId, ref, "text/plain")

        val cases = mockk<SchoolCaseRepository>()
        val sources = mockk<SourceRepository>()
        every { cases.findById(caseId) } returns Optional.of(mockk<SchoolCase> { every { id } returns caseId })
        every { sources.findById(source.id) } returns Optional.of(source)
        val router = mockk<ModelRouter>()
        every { router.available(ModelRole.TEXT) } returns true
        every { router.available(ModelRole.VISION) } returns false
        every { router.complete(ModelRole.TEXT, any(), any()) } returns
            """{"translated":"Jamie must bring sports shoes on Friday. This applies to the whole class."}"""

        val reading = SourceReadingService(cases, sources, noTranslations(), documents, router)
            .read(caseId, source.id, UserLanguage.EN)

        assertThat(reading.originalText).contains("Turnschuhe")
        assertThat(reading.translatedText).contains("sports shoes")
        assertThat(reading.translated).isTrue()
    }

    @Test
    fun `skips the model when the document is already English`() {
        val documents = de.schoolagent.documents.FileSystemDocumentStore(tempDir.toString())
        val original = "Jamie must bring sports shoes on Friday."
        val ref = documents.put(original.toByteArray())
        val caseId = UUID.randomUUID()
        val source = source(caseId, ref, "text/plain")

        val cases = mockk<SchoolCaseRepository>()
        val sources = mockk<SourceRepository>()
        every { cases.findById(caseId) } returns Optional.of(mockk<SchoolCase> { every { id } returns caseId })
        every { sources.findById(source.id) } returns Optional.of(source)
        val router = mockk<ModelRouter>(relaxed = true)
        every { router.available(any()) } returns true

        val reading = SourceReadingService(cases, sources, noTranslations(), documents, router)
            .read(caseId, source.id, UserLanguage.EN)

        assertThat(reading.translated).isFalse()
        assertThat(reading.translatedText).isEqualTo(original)
        io.mockk.verify(exactly = 0) { router.complete(any(), any(), any()) }
    }

    @Test
    fun `long translation includes the end of the document instead of truncating it`() {
        val documents = de.schoolagent.documents.FileSystemDocumentStore(tempDir.toString())
        val original = "Jamie muss am Freitag Turnschuhe mitbringen. ".repeat(850) + "WICHTIG AM ENDE"
        val ref = documents.put(original.toByteArray())
        val caseId = UUID.randomUUID(); val source = source(caseId, ref, "text/plain")
        val cases = mockk<SchoolCaseRepository>(); val sources = mockk<SourceRepository>()
        every { cases.findById(caseId) } returns Optional.of(SchoolCase(id = caseId, title = "Long letter"))
        every { sources.findById(source.id) } returns Optional.of(source)
        val router = mockk<ModelRouter>()
        every { router.available(ModelRole.TEXT) } returns true
        every { router.complete(ModelRole.TEXT, any(), any()) } answers {
            com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(mapOf("translated" to thirdArg<String>().substringAfter("Document:\n")))
        }
        val reading = SourceReadingService(cases, sources, noTranslations(), documents, router).read(caseId, source.id, UserLanguage.EN)
        assertThat(reading.translatedText).endsWith("WICHTIG AM ENDE")
        io.mockk.verify(atLeast = 9) { router.complete(ModelRole.TEXT, any(), any()) }
    }

    @Test
    fun `translation chunks retain all content and prefer paragraph boundaries`() {
        val text = ("Schule und Eltern: " + "Wort ".repeat(410) + "\n\n").repeat(8) + "Letzte Seite 📚"
        val chunks = SourceReadingService.translationChunks(text)
        assertThat(chunks.joinToString("")).isEqualTo(text)
        assertThat(chunks).allMatch { it.length <= 4000 }
        assertThat(chunks.dropLast(1)).allMatch { it.endsWith("\n\n") }
    }

    @Test
    fun `failed later chunk does not cache a partial translation as a complete document`() {
        val documents = de.schoolagent.documents.FileSystemDocumentStore(tempDir.toString())
        val original = "Die Schule informiert die Eltern. ".repeat(150)
        val ref = documents.put(original.toByteArray())
        val caseId = UUID.randomUUID(); val source = source(caseId, ref, "text/plain")
        val cases = mockk<SchoolCaseRepository>(); val sources = mockk<SourceRepository>()
        every { cases.findById(caseId) } returns Optional.of(SchoolCase(id = caseId, title = "Letter"))
        every { sources.findById(source.id) } returns Optional.of(source)
        val router = mockk<ModelRouter>()
        every { router.available(ModelRole.TEXT) } returns true
        every { router.complete(ModelRole.TEXT, any(), any()) } returns """{"translated":"First translated part"}""" andThenThrows de.schoolagent.ai.AiUnavailableException("Read timed out")
        val translations = noTranslations()
        val reading = SourceReadingService(cases, sources, translations, documents, router).read(caseId, source.id, UserLanguage.EN)
        assertThat(reading.translated).isFalse()
        assertThat(reading.originalText).isEqualTo(original.trim())
        assertThat(reading.notice).contains("Translation is unavailable")
        io.mockk.verify(exactly = 0) { translations.save(any<de.schoolagent.domain.model.SourceTranslation>()) }
    }

    private fun source(caseId: UUID, ref: String, mime: String) = Source(
        caseId = caseId,
        sourceType = SourceType.PORTAL_LETTER,
        externalId = "letter-${UUID.randomUUID()}",
        dedupKey = DedupKeys.source(SourceType.PORTAL_LETTER, "letter-${UUID.randomUUID()}"),
        contentHash = ref,
        title = "Elternbrief",
        storageRef = ref,
        mimeType = mime,
        createdAt = Instant.now(),
        updatedAt = Instant.now(),
    )
}
