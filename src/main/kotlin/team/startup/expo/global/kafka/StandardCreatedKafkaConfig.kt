package team.startup.expo.global.kafka

import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.common.TopicPartition
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.event.EventListener
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.dao.TransientDataAccessException
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory
import org.springframework.kafka.core.ConsumerFactory
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.event.ConsumerFailedToStartEvent
import org.springframework.kafka.event.ConsumerStoppedEvent
import org.springframework.kafka.event.NonResponsiveConsumerEvent
import org.springframework.kafka.listener.ContainerProperties
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer
import org.springframework.kafka.listener.DefaultErrorHandler
import org.springframework.kafka.listener.RetryListener
import org.springframework.kafka.support.ExceptionMatcher
import org.springframework.kafka.support.LoggingProducerListener
import org.springframework.kafka.support.ProducerListener
import org.springframework.transaction.CannotCreateTransactionException
import org.springframework.util.backoff.ExponentialBackOff
import org.springframework.util.backoff.FixedBackOff
import java.sql.SQLException
import java.sql.SQLRecoverableException
import java.sql.SQLTransientException

@Configuration
@ConditionalOnProperty(name = ["expo.standard-created.enabled"], havingValue = "true")
class StandardCreatedKafkaConfig(
    @Value("\${spring.kafka.bootstrap-servers:}") bootstrapServers: String,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    init {
        require(bootstrapServers.isNotBlank()) { "KAFKA_BOOTSTRAP_SERVERS is required when StandardCreated is enabled" }
    }

    @Bean
    fun standardCreatedProducerListener(): ProducerListener<Any, Any> =
        LoggingProducerListener<Any, Any>().apply { setIncludeContents(false) }

    @Bean
    fun standardCreatedContainerFactory(
        consumerFactory: ConsumerFactory<String, String>,
        kafkaTemplate: KafkaTemplate<String, String>,
    ): ConcurrentKafkaListenerContainerFactory<String, String> {
        val recoverer = DeadLetterPublishingRecoverer(kafkaTemplate) { record, _ -> TopicPartition(record.topic() + ".dlt", -1) }
        recoverer.setFailIfSendResultIsError(true)
        val backOff =
            ExponentialBackOff(1_000L, 2.0).apply {
                maxInterval = 10_000L
                maxAttempts = 5L
            }
        val handler = DefaultErrorHandler(recoverer, backOff)
        val retryable =
            ExceptionMatcher
                .forAllowList()
                .add(TransientDataAccessException::class.java)
                .add(DataAccessResourceFailureException::class.java)
                .add(CannotCreateTransactionException::class.java)
                .add(SQLTransientException::class.java)
                .add(SQLRecoverableException::class.java)
                .traverseCauses(true)
                .build()
        handler.addNotRetryableExceptions(IllegalArgumentException::class.java)
        handler.setBackOffFunction { _, exception ->
            val connectionFailure =
                generateSequence<Throwable>(exception) { it.cause }
                    .filterIsInstance<SQLException>()
                    .any { it.sqlState?.startsWith("08") == true || it.sqlState in setOf("57P01", "57P02", "57P03") }
            if (retryable.match(exception) || connectionFailure) backOff else FixedBackOff(0L, 0L)
        }
        handler.setResetStateOnExceptionChange(false)
        handler.setResetStateOnRecoveryFailure(false)
        handler.setRetryListeners(
            object : RetryListener {
                override fun failedDelivery(
                    record: ConsumerRecord<*, *>,
                    exception: Exception?,
                    attempt: Int,
                ) {
                    log.warn(
                        "StandardCreated delivery failed: partition={}, offset={}, attempt={}, type={}",
                        record.partition(),
                        record.offset(),
                        attempt,
                        exception?.cause?.javaClass?.simpleName ?: exception?.javaClass?.simpleName,
                    )
                }

                override fun recoveryFailed(
                    record: ConsumerRecord<*, *>,
                    original: Exception?,
                    failure: Exception,
                ) {
                    log.error(
                        "StandardCreated DLT publication failed: partition={}, offset={}, type={}",
                        record.partition(),
                        record.offset(),
                        failure.javaClass.simpleName,
                    )
                }
            },
        )
        return ConcurrentKafkaListenerContainerFactory<String, String>().apply {
            setConsumerFactory(consumerFactory)
            setCommonErrorHandler(handler)
            containerProperties.ackMode = ContainerProperties.AckMode.RECORD
            containerProperties.isSyncCommits = true
        }
    }

    @EventListener
    fun consumerStopped(event: ConsumerStoppedEvent) {
        if (event.reason != ConsumerStoppedEvent.Reason.NORMAL) {
            log.error("Kafka consumer stopped: reason={}", event.reason)
        }
    }

    @EventListener
    fun consumerFailedToStart(event: ConsumerFailedToStartEvent) {
        log.error("Kafka consumer failed to start: type={}", event.javaClass.simpleName)
    }

    @EventListener
    fun consumerNonResponsive(event: NonResponsiveConsumerEvent) {
        log.error("Kafka consumer poll is unresponsive: elapsedMs={}", event.timeSinceLastPoll)
    }
}
