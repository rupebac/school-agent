package de.schoolagent.documents

import jakarta.mail.Session
import jakarta.mail.internet.MimeMessage
import org.apache.pdfbox.Loader
import org.apache.pdfbox.pdmodel.interactive.action.PDActionURI
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink
import org.apache.pdfbox.text.PDFTextStripper
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.io.ByteArrayInputStream
import java.util.Properties

data class ExtractedDoc(val text: String, val links: List<String>)

/**
 * Deterministic text extraction (spec §10): MIME, HTML and PDF — no AI here.
 * HTML always yields both readable text and absolute-ish link targets.
 */
object TextExtractor {

    private val URL_REGEX = Regex("""https?://[\w\-.,;:/()?#&=%~]+""")

    private fun textLinks(text: String) = URL_REGEX.findAll(text).map { it.value.trimEnd('.', ',', ';') }.distinct().toList()

    fun fromMime(raw: ByteArray): ExtractedDoc {
        val session = Session.getDefaultInstance(Properties())
        val message = MimeMessage(session, ByteArrayInputStream(raw))
        val html = StringBuilder()
        val plain = StringBuilder()
        val links = mutableListOf<String>()

        fun walk(part: jakarta.mail.Part) {
            if (part.isMimeType("multipart/*")) {
                val multipart = part.content as jakarta.mail.Multipart
                for (i in 0 until multipart.count) walk(multipart.getBodyPart(i))
            } else if (part.isMimeType("message/rfc822")) {
                (part.content as? jakarta.mail.Part)?.let { walk(it) }
            } else if (part.disposition.equals("attachment", ignoreCase = true)) {
                return
            } else if (part.isMimeType("text/plain")) {
                val body = part.content.toString()
                plain.append(body).append("\n")
                // plain-text mails spell URLs out — pick them up too
                links += URL_REGEX.findAll(body).map { it.value }.toList()
            } else if (part.isMimeType("text/html")) {
                val htmlText = part.content.toString()
                html.append(htmlText).append("\n")
                links += Jsoup.parse(htmlText).select("a[href]")
                    .eachAttr("href")
                    .filter { it.startsWith("http") }
            }
        }
        walk(message)

        return if (plain.isNotBlank()) {
            ExtractedDoc(text = plain.toString().trim(), links = links)
        } else {
            val doc = fromHtml(html.toString())
            ExtractedDoc(text = doc.text, links = (links + doc.links).distinct())
        }
    }

    fun fromHtml(html: String): ExtractedDoc {
        val doc: Document = Jsoup.parse(html)
        val links = doc.select("a[href]").eachAttr("href").filter { it.startsWith("http") }
        doc.select("script,style,nav").remove()
        doc.select("br").append("\n")
        doc.select("p,div,section,h1,h2,h3,h4,table,tr").prepend("\n").append("\n")
        doc.select("li").prepend("\n• ").append("\n")
        return ExtractedDoc(text = doc.wholeText().replace(Regex("\n{3,}"), "\n\n").trim(), links = links)
    }

    fun fromPdf(bytes: ByteArray): ExtractedDoc {
        Loader.loadPDF(bytes).use { pdf ->
            val text = PDFTextStripper().getText(pdf)
            val targets = pdf.pages.flatMap { page -> page.annotations.filterIsInstance<PDAnnotationLink>() }
                .mapNotNull { (it.action as? PDActionURI)?.uri }
                .filter { it.startsWith("http://") || it.startsWith("https://") }
            return ExtractedDoc(text = text, links = (targets + textLinks(text)).distinct())
        }
    }

    /** Renders the first [maxPages] pages as PNGs — input for the vision model on scanned PDFs. */
    fun renderPdfPages(bytes: ByteArray, maxPages: Int = 3, dpi: Float = 110f): List<ByteArray> {
        Loader.loadPDF(bytes).use { pdf ->
            val renderer = org.apache.pdfbox.rendering.PDFRenderer(pdf)
            val pages = minOf(maxPages, pdf.numberOfPages)
            return (0 until pages).map { i ->
                val image = renderer.renderImageWithDPI(i, dpi)
                val out = java.io.ByteArrayOutputStream()
                javax.imageio.ImageIO.write(image, "png", out)
                out.toByteArray()
            }
        }
    }

    /** JPEG/PNG/GIF from WhatsApp or the camera, as PNG for the vision model. */
    fun asPng(bytes: ByteArray): ByteArray {
        val image = javax.imageio.ImageIO.read(java.io.ByteArrayInputStream(bytes)) ?: return bytes
        val out = java.io.ByteArrayOutputStream()
        javax.imageio.ImageIO.write(image, "png", out)
        return out.toByteArray()
    }

    fun byMimeType(bytes: ByteArray, mimeType: String?): ExtractedDoc? = when {
        mimeType == null -> null
        mimeType.equals("message/rfc822", ignoreCase = true) -> fromMime(bytes)
        mimeType.equals("application/pdf", ignoreCase = true) -> fromPdf(bytes)
        mimeType.contains("html", ignoreCase = true) -> fromHtml(String(bytes, Charsets.UTF_8))
        mimeType.startsWith("text/", ignoreCase = true) -> String(bytes, Charsets.UTF_8).let { ExtractedDoc(it, textLinks(it)) }
        else -> null
    }
}
