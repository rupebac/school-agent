package de.schoolagent.common

import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.web.client.RestTemplate
import java.time.Clock

@Configuration
@EnableScheduling
@ConfigurationPropertiesScan(basePackages = ["de.schoolagent"])
class InfrastructureConfig {

    @Bean
    fun clock(): Clock = Clock.systemUTC()

    /** One shared outbound HTTP client with sane timeouts for Google APIs. */
    @Bean
    fun restTemplate(): RestTemplate {
        val factory = SimpleClientHttpRequestFactory()
        factory.setConnectTimeout(10_000)
        factory.setReadTimeout(30_000)
        return RestTemplate(factory)
    }
}
