package de.schoolagent.documents

import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.assertj.core.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream

class DocumentLimitsTest {
    @Test fun `normal sized PDF page cannot decode an oversized embedded image`() {
        val png = ByteArrayOutputStream().also { javax.imageio.ImageIO.write(
            java.awt.image.BufferedImage(1, 1, java.awt.image.BufferedImage.TYPE_INT_RGB), "png", it) }.toByteArray()
        val bytes = ByteArrayOutputStream().also { out -> PDDocument().use { pdf ->
            val page = PDPage(); pdf.addPage(page)
            val image = org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject.createFromByteArray(pdf, png, "synthetic")
            image.cosObject.setInt(org.apache.pdfbox.cos.COSName.WIDTH, 100000)
            image.cosObject.setInt(org.apache.pdfbox.cos.COSName.HEIGHT, 100000)
            org.apache.pdfbox.pdmodel.PDPageContentStream(pdf, page).use { it.drawImage(image, 0f, 0f, 100f, 100f) }
            pdf.save(out)
        } }.toByteArray()
        assertThatThrownBy { TextExtractor.renderPdfPages(bytes) }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("pixel limit")
    }

    @Test fun `small compressed PDF cannot allocate an enormous rendered page`() {
        val bytes = ByteArrayOutputStream().also { out -> PDDocument().use { pdf ->
            pdf.addPage(PDPage(PDRectangle(100000f, 100000f))); pdf.save(out)
        } }.toByteArray()
        assertThatThrownBy { TextExtractor.renderPdfPages(bytes) }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("pixel limit")
    }
    @Test fun `image dimensions are checked before pixel decoding`() {
        // A header declaring a huge PNG needs no huge allocation in this test.
        val image = java.awt.image.BufferedImage(1, 1, java.awt.image.BufferedImage.TYPE_INT_RGB)
        val bytes = ByteArrayOutputStream().also { javax.imageio.ImageIO.write(image, "png", it) }.toByteArray()
        java.nio.ByteBuffer.wrap(bytes).putInt(16, 100000).putInt(20, 100000)
        val crc = java.util.zip.CRC32().apply { update(bytes, 12, 17) }
        java.nio.ByteBuffer.wrap(bytes).putInt(29, crc.value.toInt())
        assertThatThrownBy { TextExtractor.asPng(bytes) }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("pixel limit")
    }
}
