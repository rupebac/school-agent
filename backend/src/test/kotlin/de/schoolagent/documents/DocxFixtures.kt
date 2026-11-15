package de.schoolagent.documents

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Generated fictional Word packages; no school documents or Office author metadata. */
object DocxFixtures {
    const val WORD_NS = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
    const val OFFICE_NS = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
    const val PACKAGE_NS = "http://schemas.openxmlformats.org/package/2006/relationships"
    fun document(body: String) = """<w:document xmlns:w="$WORD_NS" xmlns:r="$OFFICE_NS"><w:body>$body</w:body></w:document>"""
    fun create(body: String = "<w:p><w:r><w:t>Synthetic school letter. Bring a workbook.</w:t></w:r></w:p>",
               extra: Map<String, ByteArray> = emptyMap()): ByteArray = archive(linkedMapOf(
        "[Content_Types].xml" to """<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/></Types>""".toByteArray(),
        "_rels/.rels" to """<Relationships xmlns="$PACKAGE_NS"><Relationship Id="main" Type="$OFFICE_NS/officeDocument" Target="word/document.xml"/></Relationships>""".toByteArray(),
        "word/document.xml" to document(body).toByteArray(),
    ) + extra)
    fun archive(parts: Map<String, ByteArray>): ByteArray = ByteArrayOutputStream().also { out ->
        ZipOutputStream(out).use { zip -> parts.forEach { (name, bytes) ->
            zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry()
        } }
    }.toByteArray()
}
