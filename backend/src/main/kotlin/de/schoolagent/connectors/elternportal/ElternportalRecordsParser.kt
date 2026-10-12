package de.schoolagent.connectors.elternportal

import de.schoolagent.common.DedupKeys
import de.schoolagent.connectors.school.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.text.Normalizer
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/** Parses the dedicated, student-bound views. The mixed general calendar is intentionally never read. */
class ElternportalRecordsParser {
    private val dates = Regex("\\b\\d{2}\\.\\d{2}\\.\\d{4}\\b")
    private val formatter = DateTimeFormatter.ofPattern("dd.MM.uuuu").withResolverStyle(java.time.format.ResolverStyle.STRICT)

    fun identity(html: String): SchoolIdentity {
        val doc = Jsoup.parse(html)
        val students = doc.select("select[onchange*=set_child] option").map { option ->
            val label = option.text().trim()
            val group = Regex("\\s*\\(([^()]+)\\)\\s*$").find(label)
            SchoolStudent(option.attr("value"), label.substring(0, group?.range?.first ?: label.length).trim(), group?.groupValues?.get(1))
        }
        return SchoolIdentity(null, doc.selectFirst("#schule")?.text(), students)
    }

    fun matchingStudent(identity: SchoolIdentity, scope: SchoolStudentScope): SchoolStudent {
        val name = normalize(scope.name)
        val matches = identity.students.filter { student ->
            (scope.studentId == null || student.id == scope.studentId) &&
                (normalize(student.name) == name || normalize(student.name).startsWith("$name "))
        }
        return matches.singleOrNull() ?: throw SchoolConnectionException("Could not uniquely match this child in Elternportal; check the child's name or selected student in Settings")
    }

    fun requireSelected(html: String, student: SchoolStudent) {
        val doc = Jsoup.parse(html)
        val selected = doc.selectFirst("select[onchange*=set_child] option[selected]")
            ?: doc.select("select[onchange*=set_child] option").singleOrNull()
        if (selected?.attr("value") != student.id || identity(html).students.none { it == student })
            throw SchoolConnectionException("Elternportal did not return the selected child's view")
    }

    fun exams(html: String, student: SchoolStudent, url: String): SchoolRecordFeed {
        requireSelected(html, student)
        val doc = Jsoup.parse(html)
        val active = doc.select("a.active[href*=termine/liste/schulaufgaben]").singleOrNull()
        val group = active?.text()?.let { Regex("\\(([^()]+)\\)").find(it)?.groupValues?.get(1) }
        if (student.className == null || normalize(group.orEmpty()) != normalize(student.className))
            throw SchoolConnectionException("The exam timetable does not match the selected child's class")
        val table = doc.selectFirst("table.termine-table") ?: throw SchoolConnectionException("Unrecognized Elternportal exam timetable")
        val records = table.select("tr").mapNotNull { row ->
            if (row.children().any { it.tagName() == "th" } && row.children().none { it.tagName() == "td" }) return@mapNotNull null
            val cells = row.children().filter { it.tagName() == "td" }
            if (cells.size == 1 && (cells[0].hasAttr("colspan") || isEmpty(cells[0].text()))) return@mapNotNull null
            if (cells.size != 3) throw SchoolConnectionException("Unrecognized Elternportal exam row")
            val date = date(cells[0].text()) ?: throw SchoolConnectionException("Exam date is missing or invalid")
            val title = cells[2].text().trim().takeIf { it.isNotEmpty() } ?: throw SchoolConnectionException("Exam subject is missing")
            val times = Regex("\\b\\d{1,2}:\\d{2}\\b").findAll(cells[1].text()).map { LocalTime.parse(it.value.padStart(5, '0')) }.toList()
            // The calendar JSON uses positional id_0/id_1 IDs, which change when rows are inserted.
            // With no native ID, a date/topic identity withdraws the old date and imports its replacement.
            SchoolRecord(id(row) ?: fingerprint("$date|${cells[1].text()}|$title"), SchoolRecordKind.EXAM, title,
                "${cells[0].text()} · ${cells[1].text()} · $title", date, times.firstOrNull(), times.getOrNull(1), url = url)
        }
        if (records.isEmpty() && !isEmpty(table.text()))
            throw SchoolConnectionException("Elternportal did not confirm an empty exam timetable; existing exams have been kept")
        return SchoolRecordFeed(SchoolRecordKind.EXAM, SchoolFeedStatus.AVAILABLE, records, complete = true)
    }

    fun forgotten(html: String, student: SchoolStudent, url: String): List<SchoolRecordFeed> {
        requireSelected(html, student)
        val doc = Jsoup.parse(html)
        return listOf("Vergessene Hausaufgaben" to SchoolRecordKind.FORGOTTEN_HOMEWORK,
            "Vergessene Materialien" to SchoolRecordKind.FORGOTTEN_MATERIAL).map { (heading, kind) ->
            val title = doc.select("h3").singleOrNull { it.text() == heading }
                ?: throw SchoolConnectionException("Unrecognized Elternportal homework/materials section")
            val headerRow = title.parents().firstOrNull { it.hasClass("row") }
                ?: throw SchoolConnectionException("Unrecognized Elternportal report layout")
            val records = mutableListOf<SchoolRecord>()
            var row = headerRow.nextElementSibling()
            var empty = false
            while (row != null && row.hasClass("row") && row.select("h3").isEmpty()) {
                val text = row.selectFirst(".col-md-6")?.text()?.trim()
                    ?: throw SchoolConnectionException("Unrecognized Elternportal report row")
                if (isEmpty(text)) empty = true
                else if (text.isNotBlank()) {
                    val reportDate = row.selectFirst(".col-md-4")?.text()?.let(::date) ?: date(text)
                        ?: throw SchoolConnectionException("School report has no recognizable reported date")
                    records += SchoolRecord(id(row) ?: fingerprint("$kind|$reportDate|$text"), kind, kind.label,
                        "$reportDate · $text", date = reportDate, url = url)
                }
                row = row.nextElementSibling()
            }
            if (records.isEmpty() && !empty) throw SchoolConnectionException("Elternportal did not confirm an empty report section")
            SchoolRecordFeed(kind, SchoolFeedStatus.AVAILABLE, records)
        }
    }

    fun payments(html: String, student: SchoolStudent, url: String): SchoolRecordFeed {
        requireSelected(html, student)
        val table = Jsoup.parse(html).select("table").singleOrNull { it.select("thead th").text().contains("Offene Zahlungsaufforderungen") }
            ?: throw SchoolConnectionException("Unrecognized Elternportal payments section")
        val rows = table.select("tbody > tr").filter { it.text().isNotBlank() && !isEmpty(it.text()) }
        if (rows.isEmpty() && !isEmpty(table.text())) throw SchoolConnectionException("Elternportal did not confirm an empty payments section")
        val records = rows.map { row ->
            val text = row.text().trim()
            if (row.select("td").isEmpty()) throw SchoolConnectionException("Unrecognized Elternportal payment row")
            val title = row.selectFirst("strong,b")?.text()?.takeIf { it.isNotBlank() } ?: "School payment"
            val due = Regex("(?i)(?:fällig(?:\\s+am)?|zahlbar\\s+bis|zahlen\\s+bis|frist)\\s*:?\\s*(\\d{2}\\.\\d{2}\\.\\d{4})")
                .find(text)?.groupValues?.get(1)?.let(::date)
            SchoolRecord(paymentId(row) ?: throw SchoolConnectionException("Payment has no stable identifier; its layout needs review before importing"), SchoolRecordKind.PAYMENT, title, text, dueDate = due, url = url)
        }
        return SchoolRecordFeed(SchoolRecordKind.PAYMENT, SchoolFeedStatus.AVAILABLE, records)
    }

    private fun paymentId(row: Element): String? = id(row)
        ?: row.attr("data-payment-id").takeIf { it.matches(Regex("\\d+")) }
        ?: row.selectFirst("[data-payment-id]")?.attr("data-payment-id")?.takeIf { it.matches(Regex("\\d+")) }
        ?: row.select("a[href*=payments]").mapNotNull { Regex("[?&](?:id|payment_id)=(\\d+)(?:&|$)").find(it.attr("href"))?.groupValues?.get(1) }.distinct().singleOrNull()
    private fun id(row: Element): String? = row.attr("data-id").takeIf { it.matches(Regex("\\d+")) }
    private fun fingerprint(text: String) = "content:" + DedupKeys.sha256(text.trim().replace(Regex("\\s+"), " "))
    private fun date(text: String): LocalDate? = dates.find(text)?.value?.let { runCatching { LocalDate.parse(it, formatter) }.getOrNull() }
    private fun isEmpty(text: String) = text.contains("Keine Einträge", true) || text.contains("keine Einträge zu vergessenen", true)
    private fun normalize(text: String) = Normalizer.normalize(text, Normalizer.Form.NFD).replace(Regex("\\p{M}"), "").trim().lowercase().replace(Regex("\\s+"), " ")
}
