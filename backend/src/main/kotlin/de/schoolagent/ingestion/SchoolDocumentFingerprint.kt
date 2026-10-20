package de.schoolagent.ingestion

import de.schoolagent.documents.FileSystemDocumentStore
import de.schoolagent.documents.TextExtractor
import org.apache.pdfbox.Loader

/** Never ignore images/links while deciding whether new bytes are merely PDF metadata. */
object SchoolDocumentFingerprint {
    fun of(bytes: ByteArray, mime: String): String {
        require(bytes.size <= de.schoolagent.connectors.school.SchoolDocument.MAX_DOWNLOAD_BYTES) { "School document exceeds size limit" }
        if (bytes.size > TextExtractor.MAX_INPUT_BYTES) return FileSystemDocumentStore.sha256(bytes)
        if (mime == "application/pdf") {
            // Larger/unrenderable documents conservatively retain byte identity.
            return runCatching {
                Loader.loadPDF(bytes).use { require(it.numberOfPages in 1..3) }
                val pages = TextExtractor.renderPdfPages(bytes, 3, 72f)
                val text = TextExtractor.fromPdf(bytes)
                FileSystemDocumentStore.sha256((pages.map(FileSystemDocumentStore::sha256) +
                    text.text + text.links.sorted()).joinToString("\u0000").toByteArray())
            }.getOrElse { FileSystemDocumentStore.sha256(bytes) }
        }
        return FileSystemDocumentStore.sha256(if (mime.startsWith("text/"))
            bytes.toString(Charsets.UTF_8).replace("\r\n", "\n").trim().toByteArray() else bytes)
    }
}
