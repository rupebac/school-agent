package de.schoolagent.security

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

@Component
class DatabasePasswordValidation(@Value("\${spring.datasource.password}") password: String) {
    init { DeploymentValidation.password(password, "SCHOOLAGENT_DB_PASSWORD") }
}

/** Validate required secrets without putting their values into an error message. */
object DeploymentValidation {
    fun password(value: String, name: String) {
        require(value.length >= 12 && value.isNotBlank() && !value.lowercase().startsWith("change-me") &&
            !value.lowercase().startsWith("your-password") && value != "schoolagent-dev") {
            "$name must be explicitly set to a non-placeholder password of at least 12 characters"
        }
    }
}
