package team.startup.expo.domain.expo

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import team.startup.expo.global.kafka.StandardCreatedKafkaConfig

class StandardCreatedConfigurationTests {
    private val runner = ApplicationContextRunner().withUserConfiguration(StandardCreatedKafkaConfig::class.java)

    @Test
    fun `consumer configuration is absent unless explicitly enabled`() {
        runner.run { context ->
            context.startupFailure shouldBe null
            context.containsBean("standardCreatedContainerFactory") shouldBe false
        }
    }

    @Test
    fun `enabled consumer rejects missing bootstrap servers`() {
        runner.withPropertyValues("expo.standard-created.enabled=true", "spring.kafka.bootstrap-servers=").run { context ->
            generateSequence(context.startupFailure) { it.cause }
                .any { it.message == "KAFKA_BOOTSTRAP_SERVERS is required when StandardCreated is enabled" } shouldBe true
        }
    }
}
