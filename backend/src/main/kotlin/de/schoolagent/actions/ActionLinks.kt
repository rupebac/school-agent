package de.schoolagent.actions

import de.schoolagent.connectors.school.SchoolConnectionService
import de.schoolagent.domain.model.ParentAction
import de.schoolagent.domain.model.SchoolCase
import de.schoolagent.domain.repo.SchoolCaseRepository
import org.springframework.stereotype.Service
import java.net.URI

data class ActionLink(val url: String, val kind: String, val provider: String? = null)

/** A letterhead website is not a form. Preserve actual URL paths, queries and SPA fragments. */
object ActionUrls {
    fun http(raw: String?): String? = raw?.trim()?.let { value ->
        runCatching { URI(value) }.getOrNull()?.takeIf {
            it.scheme in setOf("http", "https") && !it.host.isNullOrBlank() && it.userInfo == null
        }?.toString()
    }

    fun specific(raw: String?): String? = http(raw)?.takeIf { value ->
        val uri = URI(value)
        uri.path.trimEnd('/') != "/aktuelles/elternbriefe" &&
            ((!uri.path.isNullOrBlank() && uri.path != "/") || !uri.rawQuery.isNullOrBlank() || !uri.rawFragment.isNullOrBlank())
    }

    fun referenced(raw: String?, references: Collection<String>): String? = specific(raw)?.takeIf { value ->
        references.any { http(it)?.let { reference -> URI(reference).normalize() == URI(value).normalize() } == true }
    }
}

/** Read-only navigation projection. No model calls, network requests or changes to case facts. */
@Service
class ActionLinkService(private val cases: SchoolCaseRepository, private val connections: SchoolConnectionService) {
    fun resolve(action: ParentAction): ActionLink? = resolve(action, cases.findById(action.caseId).orElse(null))

    fun resolveAll(actions: List<ParentAction>, cases: Map<java.util.UUID, SchoolCase>): Map<java.util.UUID, ActionLink?> {
        val navigation = actions.mapNotNull { cases[it.caseId]?.childId }.distinct().associateWith(connections::navigation)
        return actions.associate { action -> action.id to resolve(action, navigation[cases[action.caseId]?.childId]) }
    }

    fun resolve(action: ParentAction, case: SchoolCase?): ActionLink? = resolve(action, case?.childId?.let(connections::navigation))

    private fun resolve(action: ParentAction, navigation: SchoolConnectionService.Navigation?): ActionLink? {
        val direct = ActionUrls.specific(action.externalUrl)
        val base = navigation?.url?.let(ActionUrls::http)?.trimEnd('/')
        val provider = navigation?.provider?.name
        val text = listOfNotNull(action.title, action.description).joinToString(" ")
        val belongsToPortal = base != null && ActionUrls.http(action.externalUrl)?.let { URI(it).host == URI(base).host } == true
        if (direct != null && action.portalProvider in de.schoolagent.connectors.school.SchoolProvider.entries.map { it.name })
            return ActionLink(direct, "PORTAL", action.portalProvider)
        if (direct != null) return ActionLink(direct, if (belongsToPortal) "PORTAL" else "FORM", if (belongsToPortal) provider else null)
        val portalTask = when (provider) {
            "ELTERNPORTAL" -> Regex("(?i)\\beltern[ -]?portal\\b").containsMatchIn(text)
            "SCHULMANAGER" -> Regex("(?i)\\bschulmanager\\b").containsMatchIn(text)
            else -> false
        }
        if (base != null && (portalTask || belongsToPortal)) {
            val path = if (provider == "ELTERNPORTAL") when {
                Regex("(?i)\\bdatenfreigabe\\b|\\bdata[ -]sharing\\b|\\bdata release\\b").containsMatchIn(text) -> "/meldungen/datenfreigabe"
                Regex("(?i)\\bmeldungen\\b").containsMatchIn(text) -> "/meldungen"
                else -> ""
            } else ""
            return ActionLink(base + path, "PORTAL", provider)
        }
        return null
    }
}
