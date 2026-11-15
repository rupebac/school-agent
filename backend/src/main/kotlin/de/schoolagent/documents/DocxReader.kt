package de.schoolagent.documents

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream
import javax.xml.stream.XMLInputFactory
import javax.xml.stream.XMLStreamConstants as XML
import javax.xml.stream.XMLStreamReader

/** Bounded, read-only WordprocessingML extraction. Never extracts files or resolves external XML/relationships. */
object DocxReader {
    const val MIME_TYPE = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
    internal const val MAX_EXPANDED_BYTES = 32 * 1024 * 1024
    internal const val MAX_ENTRIES = 2048
    private const val MAX_XML_BYTES = 4 * 1024 * 1024
    private const val MAX_TEXT_CHARS = 2 * 1024 * 1024
    private const val CONTENT_TYPES = "http://schemas.openxmlformats.org/package/2006/content-types"
    private const val MAIN_TYPE = "application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"
    private val wordNamespaces = setOf("http://schemas.openxmlformats.org/wordprocessingml/2006/main", "http://purl.oclc.org/ooxml/wordprocessingml/main")
    private val relationshipNamespaces = setOf("http://schemas.openxmlformats.org/package/2006/relationships", "http://purl.oclc.org/ooxml/package/relationships")
    private val officeNamespaces = setOf("http://schemas.openxmlformats.org/officeDocument/2006/relationships", "http://purl.oclc.org/ooxml/officeDocument/relationships")
    private val stories = Regex("word/(document|header[0-9]+|footer[0-9]+|footnotes|endnotes)\\.xml")
    private val hyperlinkField = Regex("""(?i)\bHYPERLINK\s+(?:"([^"]+)"|(https?://\S+))""")

    fun read(bytes: ByteArray): ExtractedDoc {
        require(bytes.size <= TextExtractor.MAX_INPUT_BYTES) { "DOCX exceeds input limit" }
        try {
            val parts = readParts(bytes)
            var mainType = false
            xml(requireNotNull(parts["[Content_Types].xml"]) { "DOCX content types missing" }) { r ->
                if (r.eventType == XML.START_ELEMENT && r.namespaceURI == CONTENT_TYPES && r.localName == "Override" &&
                    r.getAttributeValue(null, "PartName") == "/word/document.xml") {
                    require(!mainType) { "Ambiguous DOCX main part" }
                    mainType = r.getAttributeValue(null, "ContentType") == MAIN_TYPE
                }
            }
            require(mainType) { "Unsupported Word document type" }
            var rootRelationship = false
            xml(requireNotNull(parts["_rels/.rels"]) { "DOCX root relationships missing" }) { r ->
                if (r.eventType == XML.START_ELEMENT && r.namespaceURI in relationshipNamespaces && r.localName == "Relationship" &&
                    officeNamespaces.any { r.getAttributeValue(null, "Type") == "$it/officeDocument" }) {
                    require(!rootRelationship && r.getAttributeValue(null, "Target")?.removePrefix("/") == "word/document.xml" &&
                        r.getAttributeValue(null, "TargetMode") != "External") { "Invalid DOCX main relationship" }
                    rootRelationship = true
                }
            }
            require(rootRelationship && parts.containsKey("word/document.xml")) { "DOCX main document missing" }
            val text = StringBuilder()
            val links = linkedSetOf<String>()
            fun append(value: String) {
                require(text.length.toLong() + value.length <= MAX_TEXT_CHARS) { "DOCX exceeds text limit" }
                text.append(value)
            }
            fun link(value: String?) {
                if (value != null && (value.startsWith("https://", true) || value.startsWith("http://", true))) links += value
            }
            for (name in parts.keys.filter { stories.matches(it) }.sortedWith(compareBy({ it != "word/document.xml" }, { it }))) {
                val targets = mutableMapOf<String, String>()
                val relPath = "word/_rels/${name.substringAfterLast('/')}.rels"
                parts[relPath]?.let { relationships -> xml(relationships) { r ->
                    if (r.eventType == XML.START_ELEMENT && r.namespaceURI in relationshipNamespaces && r.localName == "Relationship" &&
                        r.getAttributeValue(null, "TargetMode") == "External" &&
                        officeNamespaces.any { r.getAttributeValue(null, "Type") == "$it/hyperlink" }) {
                        val id = r.getAttributeValue(null, "Id") ?: return@xml
                        require(!targets.containsKey(id)) { "Duplicate DOCX relationship" }
                        targets[id] = r.getAttributeValue(null, "Target").orEmpty()
                    }
                } }
                var depth = 0
                var skipDepth = 0
                var textDepth = 0
                var fieldDepth = 0
                val field = StringBuilder()
                var rootChecked = false
                xml(parts.getValue(name)) { r ->
                    when (r.eventType) {
                        XML.START_ELEMENT -> {
                            depth++
                            if (!rootChecked) {
                                val expectedRoot = when {
                                    name == "word/document.xml" -> "document"
                                    name.substringAfterLast('/').startsWith("header") -> "hdr"
                                    name.substringAfterLast('/').startsWith("footer") -> "ftr"
                                    else -> name.substringAfterLast('/').removeSuffix(".xml")
                                }
                                require(r.namespaceURI in wordNamespaces && r.localName == expectedRoot) { "Invalid Word document XML" }
                                rootChecked = true
                            }
                            if (skipDepth == 0 && r.namespaceURI in wordNamespaces && r.localName in setOf("del", "moveFrom")) skipDepth = depth
                            if (skipDepth == 0 && r.namespaceURI in wordNamespaces) when (r.localName) {
                                "t" -> textDepth = depth
                                "instrText" -> { fieldDepth = depth; field.setLength(0) }
                                "tab" -> append("\t")
                                "br", "cr" -> append("\n")
                                "hyperlink" -> officeNamespaces.firstNotNullOfOrNull { r.getAttributeValue(it, "id") }?.let { link(targets[it]) }
                                "fldSimple" -> wordNamespaces.firstNotNullOfOrNull { r.getAttributeValue(it, "instr") }?.let { instruction ->
                                    hyperlinkField.find(instruction)?.let { link(it.groups[1]?.value ?: it.groups[2]?.value) }
                                }
                            }
                        }
                        XML.CHARACTERS, XML.CDATA -> if (skipDepth == 0) {
                            if (textDepth > 0) append(r.text)
                            if (fieldDepth > 0) {
                                require(field.length + r.textLength <= MAX_XML_BYTES) { "DOCX field exceeds limit" }
                                field.append(r.text)
                            }
                        }
                        XML.END_ELEMENT -> {
                            if (skipDepth == 0 && r.namespaceURI in wordNamespaces && r.localName in setOf("p", "tr")) append("\n")
                            if (skipDepth == 0 && r.namespaceURI in wordNamespaces && r.localName == "tc") append("\t")
                            if (depth == textDepth) textDepth = 0
                            if (depth == fieldDepth) {
                                hyperlinkField.find(field)?.let { link(it.groups[1]?.value ?: it.groups[2]?.value) }
                                fieldDepth = 0
                            }
                            if (depth == skipDepth) skipDepth = 0
                            depth--
                        }
                    }
                }
                require(rootChecked) { "Empty Word document XML" }
                append("\n")
            }
            val plain = text.toString().replace(Regex("[\\t ]+\\n"), "\n").replace(Regex("\n{3,}"), "\n\n").trim()
            Regex("""https?://[^\s<>"\u0000-\u001f]+""").findAll(plain).forEach { link(it.value.trimEnd('.', ',', ';', ')')) }
            return ExtractedDoc(plain, links.toList())
        } catch (e: IllegalArgumentException) {
            throw e
        } catch (_: Exception) {
            throw IllegalArgumentException("Invalid DOCX document")
        }
    }

    private fun readParts(bytes: ByteArray): Map<String, ByteArray> {
        require(bytes.size >= 4 && bytes.take(4).toByteArray().contentEquals(byteArrayOf(0x50, 0x4b, 3, 4))) { "DOCX is not a ZIP package" }
        val names = mutableSetOf<String>()
        val parts = linkedMapOf<String, ByteArray>()
        var expanded = 0L
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            val buffer = ByteArray(8192)
            while (true) {
                val entry = zip.nextEntry ?: break
                val name = entry.name
                require(names.add(name) && names.size <= MAX_ENTRIES) { "DOCX entry limit or duplicate entry" }
                require(!name.startsWith('/') && '\\' !in name && name.split('/').none { it == ".." }) { "Invalid DOCX part path" }
                require(!name.endsWith("vbaProject.bin", true)) { "Macro-enabled Word documents are unsupported" }
                val wanted = name == "[Content_Types].xml" || name == "_rels/.rels" || stories.matches(name) ||
                    name.matches(Regex("word/_rels/(document|header[0-9]+|footer[0-9]+|footnotes|endnotes)\\.xml\\.rels"))
                val output = if (wanted) ByteArrayOutputStream() else null
                var size = 0L
                while (true) {
                    val count = zip.read(buffer)
                    if (count < 0) break
                    expanded += count; size += count
                    require(expanded <= MAX_EXPANDED_BYTES && (!wanted || size <= MAX_XML_BYTES)) { "DOCX expanded content exceeds limit" }
                    output?.write(buffer, 0, count)
                }
                if (wanted) parts[name] = output!!.toByteArray()
                zip.closeEntry()
            }
        }
        return parts
    }

    private fun xml(bytes: ByteArray, visit: (XMLStreamReader) -> Unit) {
        val factory = XMLInputFactory.newFactory().apply {
            setProperty(XMLInputFactory.SUPPORT_DTD, false)
            setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false)
            xmlResolver = javax.xml.stream.XMLResolver { _, _, _, _ -> throw IllegalArgumentException("External XML is forbidden") }
        }
        val reader = factory.createXMLStreamReader(ByteArrayInputStream(bytes))
        try {
            var depth = 0; var nodes = 0
            while (reader.hasNext()) {
                val event = reader.next()
                require(event != XML.DTD && event != XML.ENTITY_REFERENCE) { "DOCX XML entities are forbidden" }
                if (event == XML.START_ELEMENT) { depth++; nodes++; require(depth <= 128 && nodes <= 200000) { "DOCX XML structure exceeds limit" } }
                visit(reader)
                if (event == XML.END_ELEMENT) depth--
            }
        } finally { reader.close() }
    }
}
