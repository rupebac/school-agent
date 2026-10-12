package de.schoolagent.inbox

import de.schoolagent.domain.repo.SchoolCaseRepository
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

@Component
class CaseRelevanceJob(private val cases: SchoolCaseRepository, private val relevance: CaseRelevanceService) {
    private val running = AtomicBoolean()
    private val executor = Executors.newSingleThreadExecutor { task -> Thread(task, "case-relevance").apply { isDaemon = true } }
    private val log = org.slf4j.LoggerFactory.getLogger(javaClass)

    @Scheduled(initialDelay = 10000, fixedDelay = 60000)
    fun schedule() {
        if (!running.compareAndSet(false, true)) return
        executor.execute {
            try {
                var assessed = 0
                for (c in cases.findAll().sortedByDescending { it.openedAt }) {
                    if (Thread.currentThread().isInterrupted || assessed >= 5) break
                    runCatching { relevance.refresh(c.id) }.onSuccess { if (it) assessed++ }
                        .onFailure { log.warn("Relevance unavailable for {}: {}", c.id, it.javaClass.simpleName) }
                }
            } finally { running.set(false) }
        }
    }

    @jakarta.annotation.PreDestroy
    fun stop() { executor.shutdownNow() }
}
