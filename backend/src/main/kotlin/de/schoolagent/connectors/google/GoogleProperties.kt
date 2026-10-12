package de.schoolagent.connectors.google

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "schoolagent.google")
class GoogleProperties {
    /** One family OAuth client; refresh tokens and school calendars belong to each child. */
    var clientId: String = ""
    var clientSecret: String = ""
    var redirectUri: String = "http://localhost:8080/api/google/callback"
    var calendarId: String = "primary"
    var schoolCalendarName: String = "Jamie – Schule"
    var successRedirectUri: String = "http://127.0.0.1:4200/settings"
    var scopes: List<String> = listOf(
        "https://www.googleapis.com/auth/gmail.readonly",
        "https://www.googleapis.com/auth/calendar",
        "https://www.googleapis.com/auth/calendar.events",
    )
    var pollingEnabled: Boolean = false
    var gmailQuery: String = "in:inbox"
}
