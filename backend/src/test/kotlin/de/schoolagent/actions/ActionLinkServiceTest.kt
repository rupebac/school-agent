package de.schoolagent.actions

import de.schoolagent.connectors.school.SchoolConnectionService
import de.schoolagent.connectors.school.SchoolProvider
import de.schoolagent.domain.model.ParentAction
import de.schoolagent.domain.model.SchoolCase
import de.schoolagent.domain.repo.SchoolCaseRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

class ActionLinkServiceTest {
    private val connections = mockk<SchoolConnectionService>(relaxed = true)
    private val cases = mockk<SchoolCaseRepository>()
    private val service = ActionLinkService(cases, connections)
    private val kid = UUID.randomUUID()
    private val case = SchoolCase(childId = kid, title = "Data sharing")

    @Test fun `letterhead homepage becomes the child's actual consent page with an honest label`() {
        every { connections.navigation(kid) } returns SchoolConnectionService.Navigation(SchoolProvider.ELTERNPORTAL, "https://school.eltern-portal.org")
        val action = ParentAction(caseId = case.id, title = "Update Datenfreigabe in Elternportal under Meldungen",
            externalUrl = "https://school.example/", dedupKey = "consent")
        assertThat(service.resolve(action, case)).isEqualTo(ActionLink("https://school.eltern-portal.org/meldungen/datenfreigabe", "PORTAL", "ELTERNPORTAL"))
        assertThat(action.externalUrl).isEqualTo("https://school.example/")
        verify(exactly = 1) { connections.navigation(kid) }
        verify(exactly = 0) { connections.credentials(any()) }
    }

    @Test fun `real form links preserve query parameters and SPA fragments`() {
        val url = "https://forms.example/register?class=5#/consent"
        val action = ParentAction(caseId = case.id, title = "Register for the trip", externalUrl = url, dedupKey = "trip")
        assertThat(service.resolve(action, case)).isEqualTo(ActionLink(url, "FORM"))
        verify(exactly = 1) { connections.navigation(kid) }
    }

    @Test fun `school homepages do not pretend to be attached forms`() {
        val action = ParentAction(caseId = case.id, title = "Return the attached form", externalUrl = "https://school.example", dedupKey = "paper")
        assertThat(service.resolve(action, case)).isNull()
    }

    @Test fun `portal navigation needs this child's configured address and never another child's`() {
        every { connections.navigation(kid) } returns null
        val action = ParentAction(caseId = case.id, title = "Update details in Elternportal", externalUrl = "https://school.example", dedupKey = "missing")
        assertThat(service.resolve(action, case)).isNull()
        assertThat(service.resolve(action, SchoolCase(title = "Family request"))).isNull()
        verify(exactly = 1) { connections.navigation(kid) }
    }

    @Test fun `specific portal links remain exact instead of being replaced with an inferred menu`() {
        every { connections.navigation(kid) } returns SchoolConnectionService.Navigation(SchoolProvider.ELTERNPORTAL, "https://school.eltern-portal.org")
        val url = "https://school.eltern-portal.org/meldungen/adressaenderung"
        val action = ParentAction(caseId = case.id, title = "Update address in Elternportal", externalUrl = url, dedupKey = "address")
        assertThat(service.resolve(action, case)).isEqualTo(ActionLink(url, "PORTAL", "ELTERNPORTAL"))
    }

    @Test fun `native Schulmanager payment links name the school portal and preserve the exact route`() {
        every { connections.navigation(kid) } returns SchoolConnectionService.Navigation(SchoolProvider.SCHULMANAGER, "https://login.schulmanager-online.de")
        val url = "https://login.schulmanager-online.de/#/modules/invoicing"
        val action = ParentAction(caseId = case.id, title = "Pay school copying fee", externalUrl = url, dedupKey = "invoice")
        assertThat(service.resolve(action, case)).isEqualTo(ActionLink(url, "PORTAL", "SCHULMANAGER"))
        verify(exactly = 0) { connections.credentials(any()) }
    }

    @Test fun `letter index is portal navigation never an online form and batch reads settings once per child`() {
        every { connections.navigation(kid) } returns SchoolConnectionService.Navigation(SchoolProvider.ELTERNPORTAL, "https://school.eltern-portal.org")
        val actions = (1..3).map { ParentAction(caseId = case.id, title = "Return signed form", externalUrl = "https://school.eltern-portal.org/aktuelles/elternbriefe", dedupKey = "index-$it") }
        assertThat(ActionUrls.specific(actions.first().externalUrl)).isNull()
        assertThat(service.resolveAll(actions, mapOf(case.id to case)).values).containsOnly(ActionLink("https://school.eltern-portal.org", "PORTAL", "ELTERNPORTAL"))
        verify(exactly = 1) { connections.navigation(kid) }
        verify(exactly = 0) { connections.credentials(any()) }
    }
    @Test fun `native portal metadata survives description translation and a later connection change`() {
        every { connections.navigation(kid) } returns SchoolConnectionService.Navigation(SchoolProvider.SCHULMANAGER, "https://login.schulmanager-online.de")
        val url = "https://former-school.example/meldungen/payments"
        val action = ParentAction(caseId = case.id, title = "Pay copying fee", description = "A translated instruction", externalUrl = url,
            dedupKey = "old-invoice", portalProvider = "ELTERNPORTAL")
        assertThat(service.resolve(action, case)).isEqualTo(ActionLink(url, "PORTAL", "ELTERNPORTAL"))
        assertThat(service.resolve(ParentAction(caseId = case.id, title = "Open Elternportal", dedupKey = "different-provider"), case)).isNull()
    }

    @Test fun `unsafe or incomplete URLs cannot produce form links`() {
        for (url in listOf("javascript:alert(1)", "https:/missing-host/form", "https://user:secret@forms.example/register")) {
            val action = ParentAction(caseId = case.id, title = "Register", externalUrl = url, dedupKey = url)
            assertThat(service.resolve(action, case)).isNull()
        }
    }

    @Test fun `reasoning accepts referenced destinations and rejects invented paths and letterhead homepages`() {
        val form = "https://forms.example/register?id=42"
        val homepage = "https://school.example/"
        val references = listOf(form, homepage)
        assertThat(ActionUrls.referenced(form, references)).isEqualTo(form)
        assertThat(ActionUrls.referenced("https://forms.example/register?id=43", references)).isNull()
        assertThat(ActionUrls.referenced(homepage, references)).isNull()
    }
}
