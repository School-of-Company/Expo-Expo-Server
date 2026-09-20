package team.startup.expo

import com.querydsl.jpa.impl.JPAQueryFactory
import io.kotest.matchers.shouldBe
import jakarta.persistence.Entity
import jakarta.persistence.EntityManager
import jakarta.persistence.Id
import jakarta.persistence.PersistenceContext
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.transaction.annotation.Transactional
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer

@SpringBootTest(properties = ["eureka.client.enabled=false", "spring.jpa.hibernate.ddl-auto=create"])
@Testcontainers
class ExpoExpoServerApplicationTests {
    @PersistenceContext
    private lateinit var entityManager: EntityManager

    @Test
    fun contextLoads() {
    }

    @Test
    @Transactional
    fun querydslReadsFromPostgres() {
        entityManager.persist(SetupProbe(1L, "expo"))
        entityManager.flush()
        entityManager.clear()

        val probe = QSetupProbe.setupProbe
        val result =
            JPAQueryFactory(entityManager)
                .selectFrom(probe)
                .where(probe.name.eq("expo"))
                .fetchOne()

        result?.name shouldBe "expo"
    }

    companion object {
        @Container
        @ServiceConnection
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:17-alpine")
    }
}

@Entity
class SetupProbe(
    @Id val id: Long,
    val name: String,
)
