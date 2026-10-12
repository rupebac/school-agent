package de.schoolagent.security

import de.schoolagent.domain.enums.Actor
import jakarta.persistence.AttributeConverter
import jakarta.persistence.Converter

/** Old installations stored person-specific actor labels; keep their private history readable. */
@Converter
class LegacyActorConverter : AttributeConverter<Actor, String> {
    override fun convertToDatabaseColumn(actor: Actor): String = actor.name
    override fun convertToEntityAttribute(value: String): Actor = Actor.entries.firstOrNull { it.name == value } ?: Actor.MANUAL
}
