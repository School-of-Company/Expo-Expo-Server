package team.startup.expo.global.kafka

import org.apache.kafka.clients.producer.ProducerConfig
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.core.ProducerFactory

@Configuration
@ConditionalOnExpression("\${expo.events.enabled:false} or \${expo.training.sms.enabled:false}")
class OutboxKafkaConfig {
    // 기본 KafkaTemplate 자동 설정과 StandardCreated의 producer listener를 유지한다.
    @Bean(defaultCandidate = false)
    fun outboxKafkaTemplate(producers: ProducerFactory<String, String>): KafkaTemplate<String, String> =
        KafkaTemplate(producers, mapOf(ProducerConfig.MAX_BLOCK_MS_CONFIG to MAX_BLOCK_MS)).apply { setProducerListener(null) }

    private companion object {
        const val MAX_BLOCK_MS = 5000
    }
}
