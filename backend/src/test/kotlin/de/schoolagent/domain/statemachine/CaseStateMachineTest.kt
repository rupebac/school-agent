package de.schoolagent.domain.statemachine

import de.schoolagent.domain.enums.CaseStatus
import de.schoolagent.domain.statemachine.CaseStateMachine.canTransition
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class CaseStateMachineTest {

    @Test
    fun `happy path from NEW to COMPLETED`() {
        val path = listOf(
            CaseStatus.NEW to CaseStatus.INGESTING,
            CaseStatus.INGESTING to CaseStatus.INVESTIGATING,
            CaseStatus.INVESTIGATING to CaseStatus.READY_FOR_REASONING,
            CaseStatus.READY_FOR_REASONING to CaseStatus.ACTION_REQUIRED,
            CaseStatus.ACTION_REQUIRED to CaseStatus.COMPLETED,
        )
        path.forEach { (from, to) ->
            assertThat(canTransition(from, to))
                .withFailMessage("expected transition $from -> $to to be allowed")
                .isTrue()
        }
    }

    @Test
    fun `self-contained source can skip investigation`() {
        assertThat(canTransition(CaseStatus.INGESTING, CaseStatus.READY_FOR_REASONING)).isTrue()
    }

    @Test
    fun `information-only path`() {
        assertThat(canTransition(CaseStatus.READY_FOR_REASONING, CaseStatus.INFORMATION_ONLY)).isTrue()
        assertThat(canTransition(CaseStatus.INFORMATION_ONLY, CaseStatus.COMPLETED)).isTrue()
        assertThat(canTransition(CaseStatus.INFORMATION_ONLY, CaseStatus.ACTION_REQUIRED)).isTrue()
    }

    @Test
    fun `illegal transitions are rejected`() {
        assertThat(canTransition(CaseStatus.NEW, CaseStatus.COMPLETED)).isFalse()
        assertThat(canTransition(CaseStatus.NEW, CaseStatus.ACTION_REQUIRED)).isFalse()
        assertThat(canTransition(CaseStatus.COMPLETED, CaseStatus.WAITING_FOR_PARENT)).isFalse()
        assertThat(canTransition(CaseStatus.CANCELLED, CaseStatus.COMPLETED)).isFalse()

        assertThrows<InvalidCaseTransitionException> {
            CaseStateMachine.assertTransition(CaseStatus.NEW, CaseStatus.COMPLETED)
        }
    }

    @Test
    fun `every state except NEW and CANCELLED can escape to NEEDS_REVIEW or CANCELLED`() {
        // CANCELLED deliberately has a single exit: reopen into INVESTIGATING.
        CaseStatus.entries
            .filter { it != CaseStatus.NEW && it != CaseStatus.CANCELLED }
            .forEach { from ->
                val escapes = CaseStateMachine.allowedFrom(from)
                assertThat(escapes)
                    .withFailMessage("$from must be able to reach NEEDS_REVIEW or CANCELLED")
                    .containsAnyOf(CaseStatus.NEEDS_REVIEW, CaseStatus.CANCELLED)
            }
    }

    @Test
    fun `closed cases reopen on new evidence`() {
        listOf(
            CaseStatus.COMPLETED,
            CaseStatus.CANCELLED,
            CaseStatus.INFORMATION_ONLY,
            CaseStatus.WAITING_FOR_PARENT,
            CaseStatus.ACTION_REQUIRED,
        ).forEach { from ->
            assertThat(canTransition(from, CaseStatus.INVESTIGATING))
                .withFailMessage("$from should be reopenable")
                .isTrue()
            assertThat(CaseStateMachine.isReopen(from, CaseStatus.INVESTIGATING)).isTrue()
        }
    }

    @Test
    fun `failed cases retry into investigation`() {
        assertThat(canTransition(CaseStatus.FAILED, CaseStatus.INVESTIGATING)).isTrue()
        assertThat(canTransition(CaseStatus.FAILED, CaseStatus.COMPLETED)).isFalse()
    }

    @Test
    fun `activity classification`() {
        assertThat(CaseStateMachine.isActive(CaseStatus.WAITING_FOR_PARENT)).isTrue()
        assertThat(CaseStateMachine.isActive(CaseStatus.NEEDS_REVIEW)).isTrue()
        assertThat(CaseStateMachine.isActive(CaseStatus.COMPLETED)).isFalse()
        assertThat(CaseStateMachine.isActive(CaseStatus.CANCELLED)).isFalse()
    }
}
