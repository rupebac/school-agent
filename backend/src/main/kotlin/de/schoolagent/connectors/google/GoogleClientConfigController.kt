package de.schoolagent.connectors.google

import org.springframework.http.CacheControl
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

/** SecurityConfig restricts the entire /api/admin namespace to administrators. */
@RestController
@RequestMapping("/api/admin/google/client")
class GoogleClientConfigController(private val config: GoogleClientConfigService) {
    @GetMapping
    fun status() = response(config.status())

    @PutMapping
    fun save(@RequestBody update: GoogleClientConfigService.Update) = response(config.save(update))

    private fun response(status: GoogleClientConfigService.Status) =
        ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(status)
}
