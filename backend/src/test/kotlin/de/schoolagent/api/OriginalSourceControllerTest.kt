package de.schoolagent.api

import de.schoolagent.documents.DocxFixtures
import de.schoolagent.documents.DocxReader
import de.schoolagent.documents.DocumentStore
import de.schoolagent.domain.enums.SourceType
import de.schoolagent.domain.model.Source
import de.schoolagent.domain.repo.SourceRepository
import de.schoolagent.domain.service.FamilyInboxService
import io.mockk.*
import org.assertj.core.api.Assertions.*
import org.junit.jupiter.api.Test
import org.springframework.security.core.userdetails.User
import java.util.Optional
import java.util.UUID

class OriginalSourceControllerTest {
    @Test fun `Word download retains exact original bytes and docx extension behind family access check`() {
        val inbox = mockk<FamilyInboxService>(relaxed = true)
        val sources = mockk<SourceRepository>()
        val documents = mockk<DocumentStore>()
        val caseId = UUID.randomUUID()
        val source = Source(caseId = caseId, sourceType = SourceType.PORTAL_LETTER, externalId = "synthetic-docx", contentHash = "synthetic-hash", dedupKey = "synthetic-docx", mimeType = DocxReader.MIME_TYPE, storageRef = "synthetic-ref")
        val bytes = DocxFixtures.create()
        every { sources.findById(source.id) } returns Optional.of(source)
        every { documents.get("synthetic-ref") } returns bytes
        val user = User.withUsername("synthetic-parent").password("unused-test-only").roles("PARENT").build()
        val response = OriginalSourceController(inbox, sources, documents).original(caseId, source.id, user)
        assertThat(response.headers.contentDisposition.filename).isEqualTo("school-document.docx")
        assertThat(response.body).isEqualTo(bytes)
        assertThat(response.headers.getFirst("Cache-Control")).contains("no-store")
        verifyOrder { inbox.accessible(caseId, user); sources.findById(source.id); documents.get("synthetic-ref") }
    }
}
