package de.schoolagent.connectors.schulmanager

import com.fasterxml.jackson.databind.JsonNode
import de.schoolagent.connectors.school.*
import org.jsoup.Jsoup
import java.time.LocalDate

/** The parent invoice history supplies explicit settlement; the unpaid widget supplies partial sums.
 * Disappearance from either list is never interpreted as payment or cancellation.
 */
internal class SchulmanagerPayments(private val call: (String, String, Any) -> JsonNode?) {
    fun read(studentId: String): SchoolRecordFeed {
        val history = runCatching { call("main", "poqa", mapOf(
            "action" to mapOf("model" to "modules/invoicing/general-invoice", "action" to "findAll",
                "parameters" to listOf(mapOf(
                    "where" to mapOf("\$or" to listOf(mapOf("createdAt" to mapOf("\$lt" to "2021-10-18")),
                        mapOf("sentTimestamp" to mapOf("\$not" to null)))),
                    "include" to listOf(
                        mapOf("association" to "items", "required" to false, "include" to listOf(
                            mapOf("association" to "studentItems", "required" to true,
                                "where" to mapOf("studentId" to mapOf("\$in" to listOf(studentId.toLong())))))),
                        mapOf("association" to "studentInvoices", "required" to true,
                            "where" to mapOf("studentId" to mapOf("\$in" to listOf(studentId.toLong())))),
                    )))),
            "uiState" to "main.modules.invoicing.studentsInvoices",
        )) }.getOrNull()
        val unpaid = runCatching { call("main", "get-unpaid-invoices-for-widget", emptyMap<String, Any>()) }.getOrNull()
        if (history == null && unpaid == null) return SchoolRecordFeed.unavailable(SchoolRecordKind.PAYMENT, "Payment records are not visible to this account")
        require(history == null || history.isArray && history.size() <= 2000)
        require(unpaid == null || unpaid.isArray && unpaid.size() <= 2000)
        val rows = linkedMapOf<String, SchoolRecord>()
        unpaid?.filter { row ->
            require(row.hasNonNull("studentId")) { "Payment student missing" }
            row.path("studentId").asText() == studentId
        }?.forEach { row ->
            val invoice = row.path("generalInvoice")
            rows[id(row)] = record(row, invoice,
                SchoolPayment.observed(row.path("sum").asText(null), row.path("paidSum").asText(null)))
        }
        history?.forEach { invoice ->
            require(invoice.path("studentInvoices").isArray && invoice.path("items").isArray)
            for (row in invoice.path("studentInvoices")) {
                require(row.path("studentId").asText() == studentId) { "Invoice history includes another child" }
                val id = id(row)
                val existing = rows[id]
                if (row.path("paid").isBoolean && row.path("paid").asBoolean()) {
                    val amounts = invoice.path("items").flatMap { item ->
                        require(item.path("studentItems").isArray)
                        item.path("studentItems").map {
                            require(it.path("studentId").asText() == studentId)
                            it.path("amount").asText().toBigDecimal().also { amount -> require(amount.signum() >= 0) }
                        }
                    }
                    val total = amounts.takeIf { it.isNotEmpty() }?.fold(java.math.BigDecimal.ZERO) { a, b -> a + b }?.toPlainString()
                    val observed = SchoolPayment.observed(total, total)
                    rows[id] = record(row, invoice, observed.copy(status = SchoolPaymentStatus.SETTLED))
                } else if (existing == null) {
                    rows[id] = record(row, invoice, SchoolPayment("EUR", null, null, SchoolPaymentStatus.UNKNOWN))
                }
            }
        }
        return SchoolRecordFeed(SchoolRecordKind.PAYMENT, SchoolFeedStatus.AVAILABLE, rows.values.toList(),
            message = if (history == null) "Only outstanding invoices are visible; settlement cannot be inferred from disappearance" else null)
    }

    private fun record(row: JsonNode, invoice: JsonNode, payment: SchoolPayment): SchoolRecord {
        val title = invoice.path("title").asText().ifBlank { invoice.path("name").asText("School payment") }
        val due = invoice.path("dueDate").asText(null)?.takeIf { it.isNotBlank() }?.let { LocalDate.parse(it.take(10)) }
        fun money(amount: Long?) = amount?.let { java.math.BigDecimal.valueOf(it, 2).toPlainString() } ?: "unknown"
        val text = listOf(title, Jsoup.parseBodyFragment(invoice.path("description").asText()).text(),
            "Requested amount: ${money(payment.totalMinor)} ${payment.currency}",
            "Already paid: ${money(payment.paidMinor)} ${payment.currency}",
            "School payment status: ${payment.status}",
            due?.let { "Payment deadline: $it" }.orEmpty()).filter(String::isNotBlank).joinToString("\n")
        return SchoolRecord(id(row), SchoolRecordKind.PAYMENT, title, text, dueDate = due,
            url = "${SchulmanagerSession.BASE_URL}/#/modules/invoicing", payment = payment)
    }
    private fun id(row: JsonNode) = row.path("id").asText().also { require(it.toLongOrNull() != null) }
    private fun checkStudent(row: JsonNode, studentId: String) {
        require(!row.hasNonNull("studentId") || row.path("studentId").asText() == studentId)
    }
}
