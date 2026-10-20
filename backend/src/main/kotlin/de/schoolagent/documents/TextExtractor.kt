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

    const val MAX_INPUT_BYTES = 8 * 1024 * 1024
    const val MAX_IMAGE_PIXELS = 12_000_000L
    private fun checkInput(bytes: ByteArray) { require(bytes.size <= MAX_INPUT_BYTES) { "Document exceeds size limit" } }
    private fun checkPixels(width: Double, height: Double) {
        require(width.isFinite() && height.isFinite() && width > 0 && height > 0 && width * height <= MAX_IMAGE_PIXELS) {
            "Image exceeds decoded pixel limit"
        }
    }

    private val URL_REGEX = Regex("""https?://[\w\-.,;:/()?#&=%~]+""")

    private fun textLinks(text: String) = URL_REGEX.findAll(text).map { it.value.trimEnd('.', ',', ';') }.distinct().toList()

    fun fromMime(raw: ByteArray): ExtractedDoc {
        checkInput(raw)
        val session = Session.getDefaultInstance(Properties())
        val message = MimeMessage(session, ByteArrayInputStream(raw))
        val html = StringBuilder()
        val plain = StringBuilder()
        val links = mutableListOf<String>()

        var parts = 0
        fun walk(part: jakarta.mail.Part, depth: Int = 0) {
            require(depth <= 32 && ++parts <= 1000) { "MIME structure exceeds limit" }
            if (part.isMimeType("multipart/*")) {
                val multipart = part.content as jakarta.mail.Multipart
                for (i in 0 until multipart.count) walk(multipart.getBodyPart(i), depth + 1)
            } else if (part.isMimeType("message/rfc822")) {
                (part.content as? jakarta.mail.Part)?.let { walk(it, depth + 1) }
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
        checkInput(bytes)
        Loader.loadPDF(bytes).use { pdf ->
            require(pdf.numberOfPages <= 500) { "PDF exceeds page limit" }
            val text = PDFTextStripper().getText(pdf)
            val targets = pdf.pages.flatMap { page -> page.annotations.filterIsInstance<PDAnnotationLink>() }
                .mapNotNull { (it.action as? PDActionURI)?.uri }
                .filter { it.startsWith("http://") || it.startsWith("https://") }
            return ExtractedDoc(text = text, links = (targets + textLinks(text)).distinct())
        }
    }

    private fun checkRenderResources(resources: org.apache.pdfbox.pdmodel.PDResources?,
                                     visited: MutableSet<org.apache.pdfbox.cos.COSBase>, depth: Int = 0) {
        if (resources == null || !visited.add(resources.cosObject)) return
        require(depth <= 16 && visited.size <= 1000) { "PDF resources exceed rendering limit" }
        for (name in resources.xObjectNames) {
            val item = resources.getXObject(name)
            if (item is org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject)
                checkPixels(item.width.toDouble(), item.height.toDouble())
            if (item is org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject)
                checkRenderResources(item.resources, visited, depth + 1)
        }
    }

    /** Renders the first [maxPages] pages as PNGs — input for the vision model on scanned PDFs. */
    fun renderPdfPages(bytes: ByteArray, maxPages: Int = 3, dpi: Float = 110f): List<ByteArray> {
        checkInput(bytes)
        require(maxPages in 1..3 && dpi.isFinite() && dpi in 36f..150f) { "Invalid PDF rendering limits" }
        Loader.loadPDF(bytes).use { pdf ->
            val renderer = org.apache.pdfbox.rendering.PDFRenderer(pdf)
            val pages = minOf(maxPages, pdf.numberOfPages)
            val visited = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<org.apache.pdfbox.cos.COSBase, Boolean>())
            for (i in 0 until pages) checkRenderResources(pdf.getPage(i).resources, visited)
            return (0 until pages).map { i ->
                val bounds = pdf.getPage(i).cropBox
                checkPixels(kotlin.math.ceil(bounds.width.toDouble() * dpi / 72), kotlin.math.ceil(bounds.height.toDouble() * dpi / 72))
                val image = renderer.renderImageWithDPI(i, dpi)
                val out = java.io.ByteArrayOutputStream()
                javax.imageio.ImageIO.write(image, "png", out)
                out.toByteArray()
            }
        }
    }

    /** JPEG/PNG/GIF from WhatsApp or the camera, as PNG for the vision model. */
    fun asPng(bytes: ByteArray): ByteArray {
        checkInput(bytes)
        javax.imageio.ImageIO.createImageInputStream(ByteArrayInputStream(bytes)).use { input ->
            val reader = javax.imageio.ImageIO.getImageReaders(input).asSequence().firstOrNull()
                ?: throw IllegalArgumentException("Unsupported image format")
            try {
                reader.input = input
                checkPixels(reader.getWidth(0).toDouble(), reader.getHeight(0).toDouble())
                val image = reader.read(0)
                val out = java.io.ByteArrayOutputStream()
                javax.imageio.ImageIO.write(image, "png", out)
                return out.toByteArray()
            } finally { reader.dispose() }
        }
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
