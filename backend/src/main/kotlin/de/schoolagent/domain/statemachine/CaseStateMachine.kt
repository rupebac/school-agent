package de.schoolagent.domain.statemachine

import de.schoolagent.domain.enums.CaseStatus

class InvalidCaseTransitionException(from: CaseStatus, to: CaseStatus) :
    IllegalStateException("Illegal case transition $from -> $to")

/**
 * Case lifecycle rules, spec §8 + ARCHITECTURE.md §3.
 * Pure logic, no dependencies — fully unit-testable.
 */
object CaseStateMachine {

    private val transitions: Map<CaseStatus, Set<CaseStatus>> = mapOf(
        CaseStatus.NEW to setOf(
            CaseStatus.INGESTING,
        ),
        CaseStatus.INGESTING to setOf(
            CaseStatus.INVESTIGATING,
            CaseStatus.READY_FOR_REASONING,
            CaseStatus.FAILED,
            CaseStatus.NEEDS_REVIEW,
            CaseStatus.CANCELLED,
        ),
        CaseStatus.INVESTIGATING to setOf(
            CaseStatus.READY_FOR_REASONING,
            CaseStatus.FAILED,
            CaseStatus.NEEDS_REVIEW,
            CaseStatus.CANCELLED,
        ),
        CaseStatus.READY_FOR_REASONING to setOf(
            CaseStatus.INVESTIGATING, // a new email arrived before reasoning began
            CaseStatus.ACTION_REQUIRED,
            CaseStatus.INFORMATION_ONLY,
            CaseStatus.FAILED,
            CaseStatus.NEEDS_REVIEW,
            CaseStatus.CANCELLED,
        ),
        CaseStatus.ACTION_REQUIRED to setOf(
            CaseStatus.INFORMATION_ONLY, // parent corrects classification
            CaseStatus.WAITING_FOR_PARENT,
            CaseStatus.COMPLETED, // last parent action done
            CaseStatus.INVESTIGATING, // reopen: new evidence arrived
            CaseStatus.NEEDS_REVIEW,
            CaseStatus.CANCELLED,
        ),
        CaseStatus.WAITING_FOR_PARENT to setOf(
            CaseStatus.INFORMATION_ONLY,
            CaseStatus.COMPLETED,
            CaseStatus.ACTION_REQUIRED, // new action added
            CaseStatus.INVESTIGATING, // reopen: new evidence arrived
            CaseStatus.NEEDS_REVIEW,
            CaseStatus.CANCELLED,
        ),
        CaseStatus.INFORMATION_ONLY to setOf(
            CaseStatus.COMPLETED,
            CaseStatus.ACTION_REQUIRED, // parent added a to-do
            CaseStatus.INVESTIGATING, // reopen: new evidence arrived
            CaseStatus.NEEDS_REVIEW,
            CaseStatus.CANCELLED,
        ),
        CaseStatus.NEEDS_REVIEW to setOf(
            CaseStatus.INFORMATION_ONLY,
            CaseStatus.ACTION_REQUIRED,
            CaseStatus.INVESTIGATING,
            CaseStatus.COMPLETED,
            CaseStatus.FAILED,
            CaseStatus.CANCELLED,
        ),
        CaseStatus.FAILED to setOf(
            CaseStatus.INVESTIGATING, // retry with backoff, spec §40
            CaseStatus.CANCELLED,
        ),
        CaseStatus.COMPLETED to setOf(
            CaseStatus.INVESTIGATING, // reopen: new evidence arrived
            CaseStatus.CANCELLED,
        ),
        CaseStatus.CANCELLED to setOf(
            CaseStatus.INVESTIGATING, // reopen: e.g. "correction: after all it takes place"
        ),
    )

    /** Closed-ish states a new source can reopen, spec §7 (incremental communication). */
    private val reopenable = setOf(
        CaseStatus.ACTION_REQUIRED,
        CaseStatus.WAITING_FOR_PARENT,
        CaseStatus.INFORMATION_ONLY,
        CaseStatus.COMPLETED,
        CaseStatus.CANCELLED,
    )

    fun allowedFrom(from: CaseStatus): Set<CaseStatus> =
        transitions[from] ?: emptySet()

    fun canTransition(from: CaseStatus, to: CaseStatus): Boolean =
        to in allowedFrom(from)

    fun assertTransition(from: CaseStatus, to: CaseStatus) {
        if (!canTransition(from, to)) throw InvalidCaseTransitionException(from, to)
    }

    fun isReopen(from: CaseStatus, to: CaseStatus): Boolean =
        from in reopenable && to == CaseStatus.INVESTIGATING

    /** States in which the case still expects work from the family/system. */
    fun isActive(status: CaseStatus): Boolean = status !in setOf(
        CaseStatus.COMPLETED,
        CaseStatus.CANCELLED,
    )
}
