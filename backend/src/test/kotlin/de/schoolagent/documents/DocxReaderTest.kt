package de.schoolagent.documents

import org.assertj.core.api.Assertions.*
import org.junit.jupiter.api.Test

class DocxReaderTest {
    @Test fun `extracts paragraphs tables Unicode and referenced hyperlinks through the shared MIME reader`() {
        val bytes = DocxFixtures.create("""
            <w:p><w:r><w:t>Bring </w:t></w:r><w:r><w:t>a workbook — mañana.</w:t></w:r><w:r><w:br/><w:t>Next line</w:t></w:r></w:p>
            <w:tbl><w:tr><w:tc><w:p><w:r><w:t>Date</w:t></w:r></w:p></w:tc><w:tc><w:p><w:r><w:t>03.05.2027</w:t></w:r></w:p></w:tc></w:tr></w:tbl>
            <w:p><w:hyperlink r:id="form"><w:r><w:t>Register</w:t></w:r></w:hyperlink></w:p>
            <w:p><w:r><w:t>Details https://school.example/details.</w:t></w:r></w:p>
            <w:p><w:del><w:r><w:delText>Outdated deadline</w:delText></w:r></w:del><w:ins><w:r><w:t>Current deadline</w:t></w:r></w:ins></w:p>
        """, mapOf("word/_rels/document.xml.rels" to """<Relationships xmlns="${DocxFixtures.PACKAGE_NS}">
            <Relationship Id="form" Type="${DocxFixtures.OFFICE_NS}/hyperlink" Target="https://forms.example/apply?id=42&amp;mode=school" TargetMode="External"/>
            <Relationship Id="unused" Type="${DocxFixtures.OFFICE_NS}/hyperlink" Target="https://school.example/unused" TargetMode="External"/>
            </Relationships>""".toByteArray()))
        val result = TextExtractor.byMimeType(bytes, DocxReader.MIME_TYPE.uppercase() + "; charset=UTF-8")!!
        assertThat(result.text).contains("Bring a workbook — mañana.\nNext line", "Date", "03.05.2027", "Register", "Current deadline").doesNotContain("Outdated deadline")
        assertThat(result.links).containsExactly("https://forms.example/apply?id=42&mode=school", "https://school.example/details")
        assertThat(SchoolFileFormats.detect(bytes)).isEqualTo(DocxReader.MIME_TYPE)
    }

    @Test fun `includes headers footers notes and hyperlink fields without fetching their targets`() {
        val bytes = DocxFixtures.create("""<w:p><w:fldSimple w:instr='HYPERLINK "https://forms.example/simple"'><w:r><w:t>Apply</w:t></w:r></w:fldSimple></w:p>
            <w:p><w:r><w:instrText> HYPERLINK "https://forms.example/field" </w:instrText><w:t>Form</w:t></w:r></w:p>""", mapOf(
            "word/header1.xml" to """<w:hdr xmlns:w="${DocxFixtures.WORD_NS}"><w:p><w:r><w:t>Synthetic header</w:t></w:r></w:p></w:hdr>""".toByteArray(),
            "word/footer1.xml" to """<w:ftr xmlns:w="${DocxFixtures.WORD_NS}"><w:p><w:r><w:t>Synthetic footer</w:t></w:r></w:p></w:ftr>""".toByteArray(),
            "word/footnotes.xml" to """<w:footnotes xmlns:w="${DocxFixtures.WORD_NS}"><w:footnote w:id="1"><w:p><w:r><w:t>Bring water.</w:t></w:r></w:p></w:footnote></w:footnotes>""".toByteArray(),
        ))
        val result = DocxReader.read(bytes)
        assertThat(result.text).contains("Synthetic header", "Synthetic footer", "Bring water.").doesNotContain("HYPERLINK")
        assertThat(result.links).containsExactly("https://forms.example/simple", "https://forms.example/field")
    }

    @Test fun `rejects login HTML generic ZIP macro packages and malformed XML`() {
        val invalid = listOf("<html>Login required</html>".toByteArray(),
            DocxFixtures.archive(mapOf("word/document.xml" to DocxFixtures.document("").toByteArray())),
            DocxFixtures.create(extra = mapOf("word/vbaProject.bin" to byteArrayOf(1))),
            DocxFixtures.create(extra = mapOf("word/document.xml" to "<broken>".toByteArray())),
            DocxFixtures.create(extra = mapOf("_rels/.rels" to """<Relationships xmlns="${DocxFixtures.PACKAGE_NS}"><Relationship Id="main" Type="${DocxFixtures.OFFICE_NS}/officeDocument" Target="https://school.example/document.xml" TargetMode="External"/></Relationships>""".toByteArray())))
        for (bytes in invalid) {
            assertThatThrownBy { DocxReader.read(bytes) }.isInstanceOf(IllegalArgumentException::class.java)
            assertThat(SchoolFileFormats.detect(bytes)).isNull()
        }
    }

    @Test fun `XML entities and deeply nested XML fail without resolving external resources`() {
        val entity = """<!DOCTYPE document [<!ENTITY secret SYSTEM "file:///synthetic-not-a-real-file">]>${DocxFixtures.document("<w:p><w:r><w:t>&secret;</w:t></w:r></w:p>")}"""
        val nested = DocxFixtures.document("<w:r>".repeat(130) + "<w:t>Nested</w:t>" + "</w:r>".repeat(130))
        for (xml in listOf(entity, nested)) assertThatThrownBy {
            DocxReader.read(DocxFixtures.create(extra = mapOf("word/document.xml" to xml.toByteArray())))
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test fun `compressed expansion XML size and entry count are bounded`() {
        val largeXml = DocxFixtures.create("<w:p><w:r><w:t>" + "x".repeat(4 * 1024 * 1024) + "</w:t></w:r></w:p>")
        val expanded = DocxFixtures.create(extra = mapOf("word/media/synthetic.bin" to ByteArray(DocxReader.MAX_EXPANDED_BYTES)))
        val many = DocxFixtures.create(extra = (1..DocxReader.MAX_ENTRIES).associate { "word/media/part$it" to byteArrayOf() })
        for (bytes in listOf(largeXml, expanded, many)) assertThatThrownBy { DocxReader.read(bytes) }
            .isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("limit")
    }
}
