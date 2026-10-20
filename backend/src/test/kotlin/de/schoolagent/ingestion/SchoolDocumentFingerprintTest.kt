package de.schoolagent.ingestion

import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.assertj.core.api.Assertions.*
import org.junit.jupiter.api.Test
import java.awt.Color
import java.io.ByteArrayOutputStream

class SchoolDocumentFingerprintTest {
    private fun pdf(metadata: String, color: Color): ByteArray = PDDocument().use { doc ->
        val page = PDPage(); doc.addPage(page); doc.documentInformation.title = metadata
        PDPageContentStream(doc, page).use { canvas ->
            canvas.setNonStrokingColor(color); canvas.addRect(20f, 20f, 80f, 80f); canvas.fill()
        }
        ByteArrayOutputStream().also(doc::save).toByteArray()
    }
    @Test fun `PDF metadata alone is cosmetic but changed visual evidence is material`() {
        val first = pdf("Synthetic export one", Color.RED)
        val metadata = pdf("Synthetic export two", Color.RED)
        val visual = pdf("Synthetic export three", Color.BLUE)
        assertThat(first).isNotEqualTo(metadata)
        assertThat(SchoolDocumentFingerprint.of(first, "application/pdf"))
            .isEqualTo(SchoolDocumentFingerprint.of(metadata, "application/pdf"))
            .isNotEqualTo(SchoolDocumentFingerprint.of(visual, "application/pdf"))
    }
    @Test fun `large originals use byte identity without raising the extraction budget`() {
        val bytes = ByteArray(8 * 1024 * 1024 + 1)
        assertThat(SchoolDocumentFingerprint.of(bytes, "application/pdf"))
            .isEqualTo(de.schoolagent.documents.FileSystemDocumentStore.sha256(bytes))
        assertThatThrownBy { de.schoolagent.documents.TextExtractor.fromPdf(bytes) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }
    @Test fun `oversized documents fail before parsing`() {
        assertThatThrownBy { SchoolDocumentFingerprint.of(ByteArray(16 * 1024 * 1024 + 1), "application/pdf") }
            .isInstanceOf(IllegalArgumentException::class.java)
    }
}
