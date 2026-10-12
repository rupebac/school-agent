package de.schoolagent.inbox

import de.schoolagent.domain.repo.SchoolCaseRepository
import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Background only, on a dedicated thread so editing copy cannot delay scheduled notifications. */
@Component
class InboxCopyJob(private val cases: SchoolCaseRepository, private val copy: InboxCopyService, private val clock: Clock) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val running = AtomicBoolean()
    private val executor = Executors.newSingleThreadExecutor { task -> Thread(task, "inbox-copy").apply { isDaemon = true } }
    private val retryAfter = ConcurrentHashMap<UUID, Instant>()

    @Scheduled(initialDelay = 5000, fixedDelay = 60000)
    fun schedule() {
        if (!running.compareAndSet(false, true)) return
        executor.execute {
            try {
                cases.findAll().sortedWith(compareBy({ it.isClosed() }, { -it.importanceScore })).forEach { c ->
                    if (Thread.currentThread().isInterrupted) return@execute
                    if (retryAfter[c.id]?.isAfter(clock.instant()) == true) return@forEach
                    runCatching { copy.refresh(c.id) }.onFailure {
                        retryAfter[c.id] = clock.instant().plusSeconds(1800)
                        log.warn("Inbox copy unavailable for {}: {}", c.id, it.javaClass.simpleName)
                    }
                }
            } finally { running.set(false) }
        }
    }

    @PreDestroy
    fun stop() { executor.shutdownNow() }
}
