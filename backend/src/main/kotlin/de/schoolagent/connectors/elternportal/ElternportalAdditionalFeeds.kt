package de.schoolagent.connectors.elternportal

import de.schoolagent.common.DedupKeys
import de.schoolagent.connectors.school.*
import org.jsoup.Jsoup
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Dedicated selected-child pages, parsed independently so one layout failure does not hide letters. */
class ElternportalAdditionalFeeds {
    private val scope = ElternportalRecordsParser()
    private val dates = DateTimeFormatter.ofPattern("dd.MM.uuuu").withResolverStyle(java.time.format.ResolverStyle.STRICT)
    data class BoardItem(val notice: SchoolNotice, val html: String, val attachments: List<PortalBrief>)

    fun board(html: String, student: SchoolStudent, base: String): List<BoardItem> {
        scope.requireSelected(html, student)
        val doc = Jsoup.parse(html, base)
        require(doc.select("h2").any { it.text() == "Schwarzes Brett" }) { "Unknown noticeboard page" }
        val grid = doc.selectFirst(".grid") ?: throw SchoolConnectionException("Unknown noticeboard layout")
        val rows = grid.select(".grid-item > .well")
        require(rows.size <= 200)
        val items = rows.map { row ->
            val title = row.selectFirst("h4")?.text()?.takeIf { it.isNotBlank() }
                ?: throw SchoolConnectionException("Noticeboard item has no heading")
            val date = date(row.selectFirst("p.text-right")?.text().orEmpty())
                ?: throw SchoolConnectionException("Noticeboard item has no publication date")
            val identity = row.attr("data-id").takeIf { it.matches(Regex("\\d+")) }
                ?: DedupKeys.sha256("$date|$title")
            val attachments = row.select("a[href*=repo=]").map { link ->
                val uri = java.net.URI(link.absUrl("href"))
                require(uri.host == java.net.URI(base).host && uri.path.trimEnd('/') == "/aktuelles/get_file")
                val query = uri.rawQuery.orEmpty()
                val id = Regex("(?:^|&)repo=(\\d+)(?:&|$)").find(query)?.groupValues?.get(1)
                    ?: throw SchoolConnectionException("Unknown noticeboard attachment identity")
                val csrf = Regex("(?:^|&)csrf=([a-f0-9]+)(?:&|$)").find(query)?.groupValues?.get(1)
                    ?: throw SchoolConnectionException("Missing noticeboard download token")
                PortalBrief(id, null, link.text().ifBlank { "School attachment" }, null, null, null, csrf)
            }
            val clean = row.clone()
            clean.select("script,style,form,button,input,a[href*=csrf=]").remove()
            clean.select("[onclick]").forEach { it.removeAttr("onclick") }
            // Preserve ordinary links; temporary download tokens remain only in session memory.
            clean.select("a[href]").forEach { it.attr("href", it.absUrl("href")) }
            BoardItem(SchoolNotice("board:$identity", title, date.atStartOfDay(ZoneId.of("Europe/Berlin")).toInstant(),
                "$base/aktuelles/schwarzes_brett", kind = SchoolNoticeKind.NOTICEBOARD), clean.outerHtml(), attachments)
        }
        require(items.map { it.notice.id }.distinct().size == items.size) { "Ambiguous noticeboard identities" }
        return items
    }

    fun timetable(html: String, student: SchoolStudent, url: String): SchoolRecordFeed {
        scope.requireSelected(html, student)
        val doc = Jsoup.parse(html)
        val main = doc.selectFirst(".main_center") ?: throw SchoolConnectionException("Unknown substitution timetable")
        require(student.className != null && doc.select("h2").any {
            it.text().contains(Regex("(?i)(?<![\\p{L}\\d])${Regex.escape(student.className)}(?![\\p{L}\\d])"))
        }) { "The substitution timetable does not identify the selected class" }
        var day: LocalDate? = null
        val records = mutableListOf<SchoolRecord>()
        for (node in main.children()) {
            if (node.hasClass("bold") && node.hasClass("full_width")) day = date(node.text())
            if (node.tagName() != "table" || node.select(".vp_plan_head").isEmpty()) continue
            val date = day ?: throw SchoolConnectionException("Substitution date is missing")
            val headings = node.select(".vp_plan_head > td").map { it.text().trim() }
            require(headings == listOf("Std.", "Betrifft", "Vertretung", "Fach", "Raum", "Info")) { "Unknown substitution columns" }
            for (row in node.select("tr.liste_grau, tr.liste_weiss")) {
                val cells = row.children().map { it.text().trim() }
                require(cells.size == 6 && cells[0].matches(Regex("\\d{1,2}(?:\\s*[-–,]\\s*\\d{1,2})*\\.?")))
                val cancelled = cells[2].contains("Entfall", true)
                // The page is class scoped, not proof of an individual pupil's arrival/departure.
                val title = if (cancelled) "Class lesson cancelled" else "Class timetable changed"
                val text = listOf(title, "Class timetable; period: ${cells[0]}", "Affected: ${cells[1]}",
                    "Substitution: ${cells[2]}", "Subject: ${cells[3]}", "Room: ${cells[4]}", cells[5]).joinToString("\n")
                records += SchoolRecord(DedupKeys.sha256("$date|${cells[0]}|${cells[1]}"), SchoolRecordKind.TIMETABLE_CHANGE,
                    title, text, date = date, url = url,
                    timetable = SchoolTimetableChange(cancelled, cells[3].ifBlank { null }, cells[4].ifBlank { null }, cells[0]))
            }
        }
        require(main.select(".vp_plan_head").isNotEmpty()) { "Substitution timetable missing" }
        require(records.map { it.id }.distinct().size == records.size) { "Ambiguous substitution identities" }
        return SchoolRecordFeed(SchoolRecordKind.TIMETABLE_CHANGE, SchoolFeedStatus.AVAILABLE, records)
    }

    fun classbookDateFormat(html: String): DateTimeFormatter {
        val value = Jsoup.parse(html).select("a[href*=cur_date]").mapNotNull { link ->
            Regex("[?&]cur_date=([^&]+)").find(link.attr("href"))?.groupValues?.get(1)?.let {
                java.net.URLDecoder.decode(it, Charsets.UTF_8)
            }
        }.firstOrNull() ?: throw SchoolConnectionException("Unknown classbook date navigation")
        return when {
            value.matches(Regex("\\d{4}-\\d{2}-\\d{2}")) -> DateTimeFormatter.ISO_LOCAL_DATE
            value.matches(Regex("\\d{2}\\.\\d{2}\\.\\d{4}")) -> dates
            else -> throw SchoolConnectionException("Unknown classbook date format")
        }
    }

    fun homework(html: String, student: SchoolStudent, expectedDate: LocalDate, url: String): SchoolRecordFeed {
        scope.requireSelected(html, student)
        val doc = Jsoup.parse(html)
        require(doc.select("h2").any { it.text() == "Klassenbuch" })
        val selected = doc.selectFirst("input[name=cur_date]")?.attr("value").orEmpty()
        val selectedDate = date(selected) ?: runCatching { LocalDate.parse(selected) }.getOrNull()
        require(selectedDate == expectedDate) { "Classbook returned another day" }
        if (doc.select("center").any { it.text().contains("keine Einträge", true) })
            return SchoolRecordFeed(SchoolRecordKind.HOMEWORK, SchoolFeedStatus.AVAILABLE,
                message = "No classbook entries for the selected day")
        val tables = doc.select("table.table.table-bordered")
        require(tables.isNotEmpty()) { "Unknown populated classbook layout" }
        val assignments = tables.map { table ->
            val headings = table.select("thead th")
            require(headings.size == 2 && headings[0].text().isBlank() && headings[1].text().isNotBlank())
            val rows = table.select("tbody > tr").filter { it.children().firstOrNull()?.text()?.trim() in setOf("Hausaufgabe", "Hausaufgaben") }
            require(rows.isNotEmpty() && rows.all { it.children().size == 2 }) { "Unknown homework row" }
            headings[1].text() to rows.map { it.children()[1].wholeText().trim() }.filter(String::isNotBlank).joinToString("\n")
        }.groupBy({ it.first }, { it.second })
        val records = assignments.mapNotNull { (subject, paragraphs) ->
            val text = paragraphs.filter(String::isNotBlank).distinct().joinToString("\n")
            if (text.isBlank() || text.equals("keine", true) || text == "-") null
            else SchoolHomework.observed(DedupKeys.sha256("$expectedDate|$subject"), subject, text, expectedDate, url)
        }
        return SchoolRecordFeed(SchoolRecordKind.HOMEWORK, SchoolFeedStatus.AVAILABLE, records)
    }

    private fun date(text: String) = Regex("\\b\\d{2}\\.\\d{2}\\.\\d{4}\\b").find(text)?.value?.let { LocalDate.parse(it, dates) }
}
