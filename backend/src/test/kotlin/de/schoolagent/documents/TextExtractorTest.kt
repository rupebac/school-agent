package de.schoolagent.documents

import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import org.apache.pdfbox.pdmodel.interactive.action.PDActionURI
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream

class TextExtractorTest {
    @Test fun `PDF links include embedded targets and printed URLs without changing the readable text`() {
        val bytes = PDDocument().use { pdf ->
            val page = PDPage().also(pdf::addPage)
            PDPageContentStream(pdf, page).use { content ->
                content.beginText()
                content.setFont(PDType1Font(Standard14Fonts.FontName.HELVETICA), 12f)
                content.newLineAtOffset(40f, 700f)
                content.showText("Apply using this form. Details: https://school.example/details.")
                content.endText()
            }
            for (url in listOf("https://forms.example/apply?id=42", "https://forms.example/apply?id=42", "mailto:office@example.test")) {
                page.annotations.add(PDAnnotationLink().apply { action = PDActionURI().apply { uri = url } })
            }
            ByteArrayOutputStream().also(pdf::save).toByteArray()
        }
        val extracted = TextExtractor.fromPdf(bytes)
        assertThat(extracted.text).contains("Apply using this form.")
        assertThat(extracted.links).containsExactly("https://forms.example/apply?id=42", "https://school.example/details")
    }

    @Test fun `plain school notices retain URLs for grounded reasoning`() {
        val extracted = TextExtractor.byMimeType("Register: https://forms.example/apply?id=42".toByteArray(), "text/plain")!!
        assertThat(extracted.links).containsExactly("https://forms.example/apply?id=42")
    }
}
