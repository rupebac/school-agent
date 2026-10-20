package de.schoolagent.connectors.school

import de.schoolagent.connectors.elternportal.HttpElternportalClient
import de.schoolagent.connectors.elternportal.PortalBrief
import de.schoolagent.connectors.elternportal.ElternportalRecordsParser
import org.jsoup.Jsoup
import java.time.LocalDate
import org.springframework.stereotype.Service
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Service
class ElternportalSchoolConnector : SchoolConnector {
    internal var clientFactory: () -> HttpElternportalClient = { HttpElternportalClient() }
    override val provider = SchoolProvider.ELTERNPORTAL
    override fun connect(credentials: SchoolCredentials): SchoolSession {
        val client = clientFactory()
        client.login(credentials.url, credentials.username, credentials.password)
        return object : SchoolSession {
            override val canRefreshDocuments = credentials.allowReceiptDownloads
            override val canAcquireDocuments = credentials.allowReceiptDownloads
            private val parser = ElternportalRecordsParser()
            private val additional = de.schoolagent.connectors.elternportal.ElternportalAdditionalFeeds()
            private val board = mutableMapOf<String, de.schoolagent.connectors.elternportal.ElternportalAdditionalFeeds.BoardItem>()
            private val log = org.slf4j.LoggerFactory.getLogger(javaClass)
            private fun failed(kind: SchoolRecordKind, error: Throwable): SchoolRecordFeed {
                log.warn("Elternportal section {} failed errorType={}", kind, error.javaClass.simpleName)
                return SchoolRecordFeed(kind, SchoolFeedStatus.FAILED, message =
                    (error as? SchoolConnectionException)?.message ?: "Could not read this section; it will be retried")
            }
            private var dashboard = client.dashboard()
            override val identity = parser.identity(dashboard)
            private var student: SchoolStudent? = null
            override fun selectStudent(scope: SchoolStudentScope): SchoolStudent {
                val match = parser.matchingStudent(identity, scope)
                val selected = Jsoup.parse(dashboard).selectFirst("select[onchange*=set_child] option[selected]")?.attr("value")
                if (selected != match.id) {
                    client.selectChild(match.id)
                    dashboard = client.dashboard()
                }
                parser.requireSelected(dashboard, match)
                student = match
                return match
            }
            override fun records(scope: SchoolStudentScope, from: LocalDate, to: LocalDate): List<SchoolRecordFeed> {
                val child = student ?: throw SchoolConnectionException("Select the matching portal child before syncing")
                fun available(path: String) = Jsoup.parse(dashboard).select("a[href]").any { it.attr("href").trimStart('/').startsWith(path) }
                val feeds = mutableListOf<SchoolRecordFeed>()
                val base = credentials.url.trimEnd('/')
                if (scope.className != null && !scope.className.equals(child.className, ignoreCase = true))
                    feeds += SchoolRecordFeed.unavailable(SchoolRecordKind.EXAM, "The portal class is ${child.className}; update this child's class in Settings to sync exams. Letters and other sections still sync.")
                else if (!available("service/termine")) feeds += SchoolRecordFeed.unavailable(SchoolRecordKind.EXAM, "This school does not expose an exam timetable")
                else feeds += runCatching { parser.exams(client.authenticatedPage("service/termine/liste/schulaufgaben"), child,
                    "$base/service/termine/liste/schulaufgaben") }.getOrElse { failed(SchoolRecordKind.EXAM, it) }
                val reportKinds = listOf(SchoolRecordKind.FORGOTTEN_HOMEWORK, SchoolRecordKind.FORGOTTEN_MATERIAL)
                if (!available("service/hausaufgaben_material")) feeds += reportKinds.map { SchoolRecordFeed.unavailable(it, "This school does not expose these reports") }
                else feeds += runCatching { parser.forgotten(client.authenticatedPage("service/hausaufgaben_material"), child,
                    "$base/service/hausaufgaben_material") }.getOrElse { error -> reportKinds.map { failed(it, error) } }
                if (!available("meldungen/payments")) feeds += SchoolRecordFeed.unavailable(SchoolRecordKind.PAYMENT, "This school does not expose payment requests")
                else feeds += runCatching { parser.payments(client.authenticatedPage("meldungen/payments"), child,
                    "$base/meldungen/payments") }.getOrElse { failed(SchoolRecordKind.PAYMENT, it) }
                if (!available("service/vertretungsplan")) feeds += SchoolRecordFeed.unavailable(SchoolRecordKind.TIMETABLE_CHANGE, "This school does not expose a substitution timetable")
                else feeds += runCatching { additional.timetable(client.authenticatedPage("service/vertretungsplan"), child,
                    "$base/service/vertretungsplan") }.getOrElse { failed(SchoolRecordKind.TIMETABLE_CHANGE, it) }
                if (!available("service/klassenbuch")) feeds += SchoolRecordFeed.unavailable(SchoolRecordKind.HOMEWORK, "This school does not expose assigned homework")
                else feeds += runCatching {
                    val format = additional.classbookDateFormat(client.authenticatedPage("service/klassenbuch"))
                    val homework = (0L..6L).flatMap { offset ->
                        val day = from.minusDays(offset)
                        val path = "service/klassenbuch?cur_date=${java.net.URLEncoder.encode(day.format(format), Charsets.UTF_8)}"
                        additional.homework(client.authenticatedPage(path), child, day, "$base/$path").records
                    }
                    SchoolRecordFeed(SchoolRecordKind.HOMEWORK, SchoolFeedStatus.AVAILABLE, homework,
                        message = "Checks assignments from the last seven days; publication dates are not deadlines")
                }
                    .getOrElse { failed(SchoolRecordKind.HOMEWORK, it) }
                return feeds
            }
            override fun noticeFeeds(studentId: String?): List<SchoolNoticeFeed> {
                val feeds = super.noticeFeeds(studentId).toMutableList()
                if (Jsoup.parse(dashboard).select("a[href*=aktuelles/schwarzes_brett]").isEmpty())
                    feeds += SchoolNoticeFeed(SchoolNoticeKind.NOTICEBOARD, SchoolFeedStatus.UNAVAILABLE, message = "This school does not expose a noticeboard")
                else feeds += try {
                    val rows = additional.board(client.authenticatedPage("aktuelles/schwarzes_brett"),
                        requireNotNull(student), credentials.url.trimEnd('/'))
                    rows.forEach { board[it.notice.id] = it }
                    SchoolNoticeFeed(SchoolNoticeKind.NOTICEBOARD, SchoolFeedStatus.AVAILABLE, rows.map { it.notice })
                } catch (_: Exception) { SchoolNoticeFeed(SchoolNoticeKind.NOTICEBOARD, SchoolFeedStatus.FAILED,
                    message = "Could not verify the noticeboard; existing notices are retained") }
                feeds += SchoolNoticeFeed(SchoolNoticeKind.MESSAGE, SchoolFeedStatus.UNAVAILABLE,
                    message = "No verified incoming-message read view is exposed by this portal connection")
                return feeds
            }
            private val briefs = mutableMapOf<String, PortalBrief>()
            override fun notices(studentId: String?): List<SchoolNotice> = client.fetchElternbriefe(student
                ?: throw SchoolConnectionException("Select the matching portal child before reading letters")).map {
                briefs[it.repoId] = it
                val date = runCatching {
                    LocalDateTime.parse(it.dateText, DateTimeFormatter.ofPattern("dd.MM.yyyy, HH:mm"))
                        .atZone(ZoneId.of("Europe/Berlin")).toInstant()
                }.getOrNull()
                SchoolNotice(it.repoId, it.title, date, "${credentials.url}/aktuelles/elternbriefe")
            }
            override fun documents(notice: SchoolNotice, studentId: String?): List<SchoolDocument> {
                parser.requireSelected(client.dashboard(), student ?: throw SchoolConnectionException("Select the matching portal child before reading documents"))
                if (notice.kind == SchoolNoticeKind.NOTICEBOARD) {
                    val item = board.getValue(notice.id)
                    require(Jsoup.parseBodyFragment(item.html).select("img").isEmpty()) {
                        "Noticeboard inline media needs a verified download path"
                    }
                    if (item.attachments.isNotEmpty() && !credentials.allowReceiptDownloads)
                        throw SchoolConnectionException("Noticeboard PDF downloads require receipt permission")
                    val attachments = item.attachments.map { attachment ->
                        val bytes = client.downloadFile(attachment)
                        require(bytes.take(5).toByteArray().contentEquals("%PDF-".toByteArray())) { "Invalid noticeboard PDF" }
                        SchoolDocument(attachment.repoId, attachment.title, "application/pdf", bytes)
                    }
                    return listOf(SchoolDocument("letter", notice.title, "text/html", item.html.toByteArray(), notice.receivedAt)) + attachments
                }
                if (!credentials.allowReceiptDownloads) throw SchoolConnectionException(
                    "Allow PDF downloads and automatic receipt confirmation in the school connection settings first")
                val brief = briefs.getValue(notice.id)
                val bytes = client.downloadFile(brief)
                if (!bytes.take(5).toByteArray().contentEquals("%PDF-".toByteArray())) {
                    throw SchoolConnectionException("Elternportal returned an invalid PDF; please test the connection again")
                }
                return listOf(SchoolDocument("letter", brief.pdfName ?: notice.title, "application/pdf", bytes))
            }
            override fun details(notice: SchoolNotice, studentId: String?): SchoolNoticeDetails =
                SchoolNoticeDetails(documents(notice, studentId),
                    if (notice.kind == SchoolNoticeKind.LETTER) SchoolResponse(receipt = briefs.getValue(notice.id).receipt) else null)
        }
    }
}
