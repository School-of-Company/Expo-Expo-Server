package team.startup.expo.global.client

import feign.FeignException
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Duration

@Configuration
class ClientCircuitBreakerConfiguration {
    @Bean
    fun downstreamCircuits(): CircuitBreakerRegistry =
        CircuitBreakerRegistry.of(
            CircuitBreakerConfig
                .custom()
                .slidingWindowSize(WINDOW_SIZE)
                .minimumNumberOfCalls(MIN_CALLS)
                .failureRateThreshold(FAILURE_RATE_THRESHOLD)
                .waitDurationInOpenState(Duration.ofSeconds(OPEN_SECONDS))
                .ignoreExceptions(FeignException.FeignClientException::class.java)
                .build(),
        )

    private companion object {
        const val WINDOW_SIZE = 20
        const val MIN_CALLS = 10
        const val FAILURE_RATE_THRESHOLD = 50f
        const val OPEN_SECONDS = 10L
    }
}
