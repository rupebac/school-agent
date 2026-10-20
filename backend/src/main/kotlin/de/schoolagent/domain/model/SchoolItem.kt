package de.schoolagent.domain.model

import jakarta.persistence.*
import java.time.Instant
import java.util.UUID

/** A portal identity outlives individual documents and follows its source through case merges. */
@Entity
@Table(name = "school_item")
class SchoolItem(
    @Id var itemKey: String,
    var childId: UUID,
    var namespace: String,
    var nativeId: String,
    var sourceId: UUID,
    var version: Int = 1,
    var manifestHash: String,
    var factHash: String,
    var changeMarker: String? = null,
    var checkedAt: Instant,
    var lastSuccessAt: Instant? = null,
    var failureCode: String? = null,
    var schoolStatus: String? = null,
    var title: String? = null,
)

@Entity
@Table(name = "school_item_revision")
class SchoolItemRevision(
    @Id var id: UUID = UUID.randomUUID(),
    var itemKey: String,
    var version: Int,
    var manifestHash: String,
    var factHash: String,
    var parts: String,
    var observedAt: Instant,
    var material: Boolean,
)
