package team.startup.expo.domain.expo

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.persistence.autoconfigure.EntityScan
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.context.ApplicationContext
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import team.startup.expo.domain.expo.entity.Expo
import team.startup.expo.domain.expo.presentation.dto.request.UpdateParticipantPhoneVerificationRequest
import team.startup.expo.domain.expo.repository.ExpoRepository
import team.startup.expo.domain.expo.service.ExpoEventSupport
import team.startup.expo.domain.expo.service.PublishExpoEventsService
import team.startup.expo.domain.expo.service.RecoverExpoEventsService
import team.startup.expo.domain.expo.service.RepublishExpoEventsService
import team.startup.expo.domain.expo.service.UpdateParticipantPhoneVerificationService
import team.startup.expo.global.exception.ExpectedException
import team.startup.expo.support.TestJwt

// KAFKA_EXPO_EVENTS_ENABLED 기본값(false)에서는 아웃박스에 쓰지 않고 릴레이도 뜨지 않는다.
@SpringBootTest(properties = ["eureka.client.enabled=false"])
@EntityScan("team.startup.expo.domain")
@Testcontainers
class ExpoEventDisabledPostgresTests {
    @Autowired
    private lateinit var jdbc: JdbcTemplate

    @Autowired
    private lateinit var expos: ExpoRepository

    @Autowired
    private lateinit var events: ExpoEventSupport

    @Autowired
    private lateinit var phoneVerification: UpdateParticipantPhoneVerificationService

    @Autowired
    private lateinit var republish: RepublishExpoEventsService

    @Autowired
    private lateinit var dbTransactions: TransactionTemplate

    @Autowired
    private lateinit var context: ApplicationContext

    @Test
    fun `꺼져 있으면 쓰기 경로가 아웃박스 행을 남기지 않고 릴레이와 재발행도 동작하지 않는다`() {
        expos.saveAndFlush(Expo(EXPO, "테스트 박람회", "설명", "2030-01-01", "2030-01-02", "광주", null, "127", "37", 0, 0))

        phoneVerification.execute(EXPO, UpdateParticipantPhoneVerificationRequest(false))
        dbTransactions.executeWithoutResult { events.changed(EXPO) }

        jdbc.queryForObject("SELECT count(*) FROM tb_expo_event_outbox", Long::class.java) shouldBe 0
        context.getBeansOfType(PublishExpoEventsService::class.java).isEmpty() shouldBe true
        context.getBeansOfType(RecoverExpoEventsService::class.java).isEmpty() shouldBe true
        assertThrows(ExpectedException::class.java) { republish.execute() }.status shouldBe HttpStatus.SERVICE_UNAVAILABLE
    }

    companion object {
        private const val EXPO = "expo-event-disabled"

        @JvmStatic
        @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("JWT_PUBLIC_KEY") { TestJwt.publicKeyPem }
        }

        @Container
        @ServiceConnection
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:17-alpine")
    }
}
