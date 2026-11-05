package team.startup.expo.global.kafka

import io.kotest.matchers.shouldBe
import org.apache.kafka.clients.producer.ProducerConfig
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.kafka.autoconfigure.KafkaAutoConfiguration
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.kafka.core.KafkaTemplate

class OutboxKafkaConfigTests {
    @Test
    fun `두 발행 기능 중 하나라도 켜지면 기본 템플릿과 별도로 outbox 템플릿을 만든다`() {
        for (events in listOf(false, true)) {
            for (sms in listOf(false, true)) {
                ApplicationContextRunner()
                    .withConfiguration(AutoConfigurations.of(KafkaAutoConfiguration::class.java))
                    .withUserConfiguration(OutboxKafkaConfig::class.java)
                    .withPropertyValues("expo.events.enabled=$events", "expo.training.sms.enabled=$sms")
                    .run { context ->
                        context.startupFailure shouldBe null
                        context.containsBean("kafkaTemplate") shouldBe true
                        context.containsBean("outboxKafkaTemplate") shouldBe (events || sms)
                        val default = context.getBean("kafkaTemplate", KafkaTemplate::class.java)
                        default.producerFactory.configurationProperties.containsKey(ProducerConfig.MAX_BLOCK_MS_CONFIG) shouldBe false
                        if (events || sms) {
                            val outbox = context.getBean("outboxKafkaTemplate", KafkaTemplate::class.java)
                            (outbox === default) shouldBe false
                            outbox.producerFactory.configurationProperties[ProducerConfig.MAX_BLOCK_MS_CONFIG] shouldBe 5000
                        }
                    }
            }
        }
    }
}
