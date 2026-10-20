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
            private val parser = ElternportalRecordsParser()
            private val log = org.slf4j.LoggerFactory.getLogger(javaClass)
            private fun failed(kind: SchoolRecordKind, error: Throwable): SchoolRecordFeed {
                val reason = (error as? SchoolConnectionException)?.message ?: error.javaClass.simpleName
                log.warn("Elternportal section {} failed: {}", kind, reason)
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
                val brief = briefs.getValue(notice.id)
                val bytes = client.downloadFile(brief)
                if (!bytes.take(5).toByteArray().contentEquals("%PDF-".toByteArray())) {
                    throw SchoolConnectionException("Elternportal returned an invalid PDF; please test the connection again")
                }
                return listOf(SchoolDocument("letter", brief.pdfName ?: notice.title, "application/pdf", bytes))
            }
        }
    }
}
