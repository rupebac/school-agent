package de.schoolagent.ai

import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

@Entity
@Table(name = "ai_response_cache")
class AiResponse(@Id val id: String, val response: String, val createdAt: Instant = Instant.now())
interface AiResponseRepository : JpaRepository<AiResponse, String>

/** Short independent transactions: cached work survives a later case rollback or restart. */
@Service
class AiResponseCache(private val responses: AiResponseRepository) {
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    fun get(key: String): String? = responses.findById(key).orElse(null)?.response

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun put(key: String, value: String) { responses.save(AiResponse(key, value)) }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun evict(key: String) { responses.deleteById(key) }
}
