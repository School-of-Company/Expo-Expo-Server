package team.startup.expo.persistence

import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import jakarta.persistence.EntityManager
import org.flywaydb.core.Flyway
import org.flywaydb.core.api.FlywayException
import org.hibernate.Hibernate
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.persistence.autoconfigure.EntityScan
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.data.jpa.repository.config.EnableJpaRepositories
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.annotation.DirtiesContext
import org.springframework.transaction.annotation.Transactional
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import team.startup.expo.domain.expo.entity.Expo
import team.startup.expo.domain.expo.repository.ExpoRepository
import team.startup.expo.domain.standard.entity.StandardProgram
import team.startup.expo.domain.standard.repository.StandardProgramRepository
import team.startup.expo.domain.training.entity.Category
import team.startup.expo.domain.training.entity.TrainingProgram
import team.startup.expo.domain.training.repository.TrainingProgramRepository
import java.sql.DriverManager

@SpringBootTest(
    classes = [PersistenceTestApplication::class],
    properties = [
        "eureka.client.enabled=false",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.flyway.enabled=true",
    ],
)
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ExpoPersistenceTests {
    @Autowired
    private lateinit var expoRepository: ExpoRepository

    @Autowired
    private lateinit var trainingProgramRepository: TrainingProgramRepository

    @Autowired
    private lateinit var standardProgramRepository: StandardProgramRepository

    @Autowired
    private lateinit var entityManager: EntityManager

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    @Autowired
    private lateinit var flyway: Flyway

    @BeforeEach
    fun clearTables() {
        jdbcTemplate.execute("TRUNCATE TABLE tb_training_program, tb_standard_program, tb_expo RESTART IDENTITY")
    }

    @Test
    @Transactional
    fun `Expo의 모든 필드를 PostgreSQL에 저장하고 다시 읽는다`() {
        val expo = expo(title = "박람회", description = "상세".repeat(600), coverImage = null)

        expoRepository.saveAndFlush(expo)
        entityManager.clear()

        val reloaded = expoRepository.findById(EXPO_ID).orElseThrow()
        reloaded.id shouldBe EXPO_ID
        reloaded.title shouldBe "박람회"
        reloaded.description shouldBe "상세".repeat(600)
        reloaded.startedDay shouldBe "2026-09-22"
        reloaded.finishedDay shouldBe "2026-09-23"
        reloaded.location shouldBe "서울"
        reloaded.coverImage shouldBe null
        reloaded.x shouldBe "127.123456"
        reloaded.y shouldBe "37.123456"
        reloaded.applicationPerson shouldBe 100L
        reloaded.yesterdayApplicationPerson shouldBe 90L
    }

    @Test
    @Transactional
    fun `동일 ID의 교체 저장은 한 행을 유지하고 전달한 카운터로 덮어쓴다`() {
        expoRepository.saveAndFlush(expo())
        entityManager.clear()

        expoRepository.saveAndFlush(expo(title = "수정 박람회", applicationPerson = 0L, yesterdayApplicationPerson = 0L))
        entityManager.clear()

        expoRepository.count() shouldBe 1L
        expoRepository.findById(EXPO_ID).orElseThrow().let {
            it.id shouldBe EXPO_ID
            it.title shouldBe "수정 박람회"
            it.applicationPerson shouldBe 0L
            it.yesterdayApplicationPerson shouldBe 0L
        }
    }

    @Test
    @Transactional
    fun `카운터 메서드 변경을 flush clear 뒤 확인한다`() {
        expoRepository.saveAndFlush(expo())
        entityManager.clear()

        expoRepository.findById(EXPO_ID).orElseThrow().plusApplicationPerson()
        entityManager.flush()
        entityManager.clear()
        expoRepository.findById(EXPO_ID).orElseThrow().let {
            it.applicationPerson shouldBe 101L
            it.yesterdayApplicationPerson shouldBe 90L
            it.saveYesterdayApplicationPerson(101L)
        }
        entityManager.flush()
        entityManager.clear()

        expoRepository.findById(EXPO_ID).orElseThrow().let {
            it.applicationPerson shouldBe 101L
            it.yesterdayApplicationPerson shouldBe 101L
        }
    }

    @Test
    fun `Expo 데이터베이스 제약이 잘못된 값을 거부한다`() {
        assertRejected(
            "INSERT INTO tb_expo VALUES (?, NULL, 'd', '2026-09-22', '2026-09-23', 'l', NULL, 'x', 'y', 1, 1)",
            EXPO_ID,
        )
        assertRejected(
            "INSERT INTO tb_expo VALUES (?, 't', 'd', '2026-09-22', '2026-09-23', 'l', NULL, 'x', 'y', 1, 1)",
            "x".repeat(37),
        )
        jdbcTemplate.update(
            "INSERT INTO tb_expo VALUES (?, 't', 'd', '2026-09-22', '2026-09-23', 'l', NULL, 'x', 'y', 1, 1)",
            EXPO_ID,
        )
        assertRejected(
            "INSERT INTO tb_expo VALUES (?, 't', 'd', '2026-09-22', '2026-09-23', 'l', NULL, 'x', 'y', 1, 1)",
            EXPO_ID,
        )
        assertRejected("UPDATE tb_expo SET application_person = NULL WHERE id = ?", EXPO_ID)
        assertRejected("UPDATE tb_expo SET yesterday_application_person = NULL WHERE id = ?", EXPO_ID)
    }

    @Test
    @Transactional
    fun `Training 프로그램은 identity enum 관계와 repository 조건을 보존한다`() {
        val expoA = expo()
        val expoB = expo(id = EXPO_B_ID, title = "박람회 B")
        expoRepository.saveAllAndFlush(listOf(expoA, expoB))

        val essential = trainingProgram(expo = expoA, category = Category.ESSENTIAL)
        val choice = trainingProgram(title = "선택", expo = expoB, category = Category.CHOICE)
        val nullable = trainingProgram(title = "관계 없음", expo = null)
        trainingProgramRepository.saveAllAndFlush(listOf(essential, choice, nullable))
        val essentialId = essential.id!!
        val choiceId = choice.id!!
        val nullableId = nullable.id!!
        entityManager.clear()

        trainingProgramRepository.findByExpo(expoRepository.getReferenceById(EXPO_ID)).map { it.id } shouldBe
            listOf(essentialId)
        trainingProgramRepository.findAllByIdIn(listOf(essentialId, choiceId)).map { it.id }.toSet() shouldBe
            setOf(essentialId, choiceId)
        trainingProgramRepository.findAllByIdIn(emptyList()) shouldBe emptyList()
        trainingProgramRepository.findById(nullableId).orElseThrow().expo shouldBe null
        jdbcTemplate.queryForObject(
            "SELECT category FROM tb_training_program WHERE id = ?",
            String::class.java,
            essentialId,
        ) shouldBe "ESSENTIAL"
        jdbcTemplate.queryForObject(
            "SELECT category FROM tb_training_program WHERE id = ?",
            String::class.java,
            choiceId,
        ) shouldBe "CHOICE"
        trainingProgramRepository.findById(essentialId).orElseThrow().let {
            it.title shouldBe "필수"
            it.startedAt shouldBe "2026-09-22T09:00"
            it.endedAt shouldBe "2026-09-22T10:00"
            it.category shouldBe Category.ESSENTIAL
            it.expo?.id shouldBe EXPO_ID
        }
    }

    @Test
    fun `Training 데이터베이스 제약이 길이와 고아 관계를 거부한다`() {
        assertRejected(
            "INSERT INTO tb_training_program (title, started_at, ended_at, category) VALUES (?, 's', 'e', 'ESSENTIAL')",
            "t".repeat(51),
        )
        assertRejected(
            "INSERT INTO tb_training_program (title, started_at, ended_at, category) VALUES ('t', ?, 'e', 'ESSENTIAL')",
            "s".repeat(21),
        )
        assertRejected(
            "INSERT INTO tb_training_program (title, started_at, ended_at, category, expo_id) VALUES ('t', 's', 'e', 'ESSENTIAL', ?)",
            EXPO_ID,
        )
    }

    @Test
    @Transactional
    fun `프로그램 수정 조회와 세 삭제 방식은 대상만 변경한다`() {
        val expoA = expo()
        val expoB = expo(id = EXPO_B_ID, title = "박람회 B")
        expoRepository.saveAllAndFlush(listOf(expoA, expoB))
        val trainingUpdate = trainingProgram(title = "연수 수정 전", expo = expoA)
        val trainingBatch = trainingProgram(title = "연수 일괄 삭제", expo = expoA)
        val trainingDerived = trainingProgram(title = "연수 조건 삭제", expo = expoA)
        val trainingKeep = trainingProgram(title = "연수 유지", expo = expoB)
        trainingProgramRepository.saveAllAndFlush(
            listOf(trainingUpdate, trainingBatch, trainingDerived, trainingKeep),
        )
        val standardUpdate = standardProgram(title = "일반 수정 전", expo = expoA)
        val standardBatch = standardProgram(title = "일반 일괄 삭제", expo = expoA)
        val standardSingle = standardProgram(title = "일반 개별 삭제", expo = null)
        val standardKeep = standardProgram(title = "일반 유지", expo = expoB)
        standardProgramRepository.saveAllAndFlush(
            listOf(standardUpdate, standardBatch, standardSingle, standardKeep),
        )
        val trainingUpdateId = trainingUpdate.id!!
        val standardUpdateId = standardUpdate.id!!

        trainingProgramRepository.saveAndFlush(
            trainingProgram(
                id = trainingUpdateId,
                title = "연수 수정 후",
                startedAt = "2026-09-22T11:00",
                endedAt = "2026-09-22T12:00",
                expo = expoA,
            ),
        )
        standardProgramRepository.saveAndFlush(
            standardProgram(
                id = standardUpdateId,
                title = "일반 수정 후",
                startedAt = "2026-09-22T13:00",
                endedAt = "2026-09-22T14:00",
                expo = expoA,
            ),
        )
        entityManager.clear()

        trainingProgramRepository.count() shouldBe 4L
        trainingProgramRepository.findById(trainingUpdateId).orElseThrow().let {
            it.id shouldBe trainingUpdateId
            it.title shouldBe "연수 수정 후"
            it.startedAt shouldBe "2026-09-22T11:00"
            it.endedAt shouldBe "2026-09-22T12:00"
            it.expo?.id shouldBe EXPO_ID
        }
        standardProgramRepository.count() shouldBe 4L
        standardProgramRepository.findByIdAndExpoId(standardUpdateId, EXPO_ID).shouldNotBeNull().let {
            it.id shouldBe standardUpdateId
            it.title shouldBe "일반 수정 후"
            it.startedAt shouldBe "2026-09-22T13:00"
            it.endedAt shouldBe "2026-09-22T14:00"
            it.expo?.id shouldBe EXPO_ID
        }
        standardProgramRepository.findByIdAndExpoId(standardUpdateId, EXPO_B_ID) shouldBe null
        standardProgramRepository.findByExpo(expoRepository.getReferenceById(EXPO_B_ID)).map { it.id } shouldBe
            listOf(standardKeep.id)
        standardProgramRepository.findById(standardSingle.id!!).orElseThrow().expo shouldBe null

        trainingProgramRepository.deleteAllInBatch(listOf(trainingBatch))
        standardProgramRepository.deleteAllInBatch(listOf(standardBatch))
        standardProgramRepository.deleteById(standardSingle.id!!)
        trainingProgramRepository.deleteByExpo(expoRepository.getReferenceById(EXPO_ID))
        entityManager.flush()
        entityManager.clear()

        trainingProgramRepository.findAll().map { it.id } shouldBe listOf(trainingKeep.id)
        standardProgramRepository.findAll().map { it.id }.toSet() shouldBe setOf(standardUpdateId, standardKeep.id)
        expoRepository.count() shouldBe 2L

        standardProgramRepository.deleteByExpo(expoRepository.getReferenceById(EXPO_ID))
        entityManager.flush()
        entityManager.clear()
        standardProgramRepository.findAll().map { it.id } shouldBe listOf(standardKeep.id)
        expoRepository.count() shouldBe 2L
    }

    @Test
    @Transactional
    fun `Standard의 Expo 관계는 reload 뒤 LAZY proxy로 남는다`() {
        val expo = expo()
        expoRepository.saveAndFlush(expo)
        val program = standardProgram(expo = expo)
        standardProgramRepository.saveAndFlush(program)
        val programId = program.id!!
        entityManager.clear()

        val reloaded = standardProgramRepository.findById(programId).orElseThrow()
        Hibernate.isInitialized(reloaded.expo) shouldBe false
        reloaded.expo?.title shouldBe "박람회"
        Hibernate.isInitialized(reloaded.expo) shouldBe true
    }

    @Test
    fun `Standard 데이터베이스 제약이 길이와 고아 관계를 거부한다`() {
        assertRejected(
            "INSERT INTO tb_standard_program (title, started_at, ended_at) VALUES (?, 's', 'e')",
            "t".repeat(51),
        )
        assertRejected(
            "INSERT INTO tb_standard_program (title, started_at, ended_at) VALUES ('t', 's', ?)",
            "e".repeat(21),
        )
        assertRejected(
            "INSERT INTO tb_standard_program (title, started_at, ended_at, expo_id) VALUES ('t', 's', 'e', ?)",
            EXPO_ID,
        )
    }

    @Test
    fun `PostgreSQL FK cascade는 부모 대상 자식만 삭제한다`() {
        val expoA = expo()
        val expoB = expo(id = EXPO_B_ID, title = "박람회 B")
        expoRepository.saveAllAndFlush(listOf(expoA, expoB))
        trainingProgramRepository.saveAllAndFlush(
            listOf(trainingProgram(expo = expoA), trainingProgram(title = "유지", expo = expoB)),
        )
        standardProgramRepository.saveAllAndFlush(
            listOf(standardProgram(expo = expoA), standardProgram(title = "유지", expo = expoB)),
        )
        entityManager.clear()

        jdbcTemplate.update("DELETE FROM tb_expo WHERE id = ?", EXPO_ID) shouldBe 1
        entityManager.clear()

        jdbcTemplate.queryForObject("SELECT COUNT(*) FROM tb_training_program", Long::class.java) shouldBe 1L
        jdbcTemplate.queryForObject("SELECT COUNT(*) FROM tb_standard_program", Long::class.java) shouldBe 1L
        jdbcTemplate.queryForObject("SELECT COUNT(*) FROM tb_expo", Long::class.java) shouldBe 1L

        jdbcTemplate.update("DELETE FROM tb_standard_program WHERE expo_id = ?", EXPO_B_ID) shouldBe 1
        jdbcTemplate.queryForObject("SELECT COUNT(*) FROM tb_expo", Long::class.java) shouldBe 1L
    }

    @Test
    fun `Flyway 재실행은 기존 행과 migration history를 바꾸지 않는다`() {
        expoRepository.saveAndFlush(expo())
        val historyCount =
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE success",
                Long::class.java,
            )

        flyway.migrate().migrationsExecuted shouldBe 0

        expoRepository.count() shouldBe 1L
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM flyway_schema_history WHERE success",
            Long::class.java,
        ) shouldBe historyCount
    }

    @Test
    fun `승인된 VARCHAR 길이와 필수 title을 PostgreSQL이 강제한다`() {
        jdbcTemplate.update(
            "INSERT INTO tb_expo VALUES (?, ?, 'd', 's', 'f', 'l', NULL, 'x', 'y', 1, 1)",
            EXPO_ID,
            "t".repeat(51),
        ) shouldBe 1
        assertRejected("UPDATE tb_expo SET started_day = ? WHERE id = ?", "s".repeat(21), EXPO_ID)
        assertRejected("UPDATE tb_expo SET finished_day = ? WHERE id = ?", "f".repeat(21), EXPO_ID)
        assertRejected("UPDATE tb_expo SET x = ? WHERE id = ?", "x".repeat(16), EXPO_ID)
        assertRejected("UPDATE tb_expo SET y = ? WHERE id = ?", "y".repeat(16), EXPO_ID)
        assertRejected(
            "INSERT INTO tb_training_program (title, started_at, ended_at, category) VALUES (NULL, 's', 'e', 'ESSENTIAL')",
        )
        assertRejected(
            "INSERT INTO tb_training_program (title, started_at, ended_at, category) VALUES ('t', 's', ?, 'ESSENTIAL')",
            "e".repeat(21),
        )
        assertRejected(
            "INSERT INTO tb_standard_program (title, started_at, ended_at) VALUES (NULL, 's', 'e')",
        )
        assertRejected(
            "INSERT INTO tb_standard_program (title, started_at, ended_at) VALUES ('t', ?, 'e')",
            "s".repeat(21),
        )
    }

    @Test
    fun `승인되지 않은 Category 문자열은 ORM 로딩에서 실패한다`() {
        val id =
            jdbcTemplate.queryForObject(
                "INSERT INTO tb_training_program (title, started_at, ended_at, category) " +
                    "VALUES ('t', 's', 'e', 'UNKNOWN') RETURNING id",
                Long::class.java,
            )!!

        assertThrows(RuntimeException::class.java) {
            trainingProgramRepository.findById(id)
        }
    }

    @Test
    fun `프로그램 저장은 저장되지 않은 Expo를 cascade persist하지 않는다`() {
        assertThrows(RuntimeException::class.java) {
            trainingProgramRepository.saveAndFlush(trainingProgram(expo = expo()))
        }
        entityManager.clear()

        expoRepository.count() shouldBe 0L
        trainingProgramRepository.count() shouldBe 0L
    }

    @Test
    fun `Flyway는 history 없는 nonempty schema를 자동 baseline하지 않는다`() {
        PostgreSQLContainer("postgres:17-alpine").use { database ->
            database.start()
            DriverManager.getConnection(database.jdbcUrl, database.username, database.password).use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute("CREATE TABLE existing_data (id BIGINT PRIMARY KEY)")
                }
            }

            assertThrows(FlywayException::class.java) {
                Flyway
                    .configure()
                    .dataSource(database.jdbcUrl, database.username, database.password)
                    .load()
                    .migrate()
            }
        }
    }

    private fun assertRejected(
        sql: String,
        vararg args: Any,
    ) {
        assertThrows(DataIntegrityViolationException::class.java) {
            jdbcTemplate.update(sql, *args)
        }
    }

    companion object {
        private const val EXPO_ID = "123456789012-1234-1234-1234-12345678"
        private const val EXPO_B_ID = "abcdefabcdef-abcd-abcd-abcd-abcdefab"

        @Container
        @ServiceConnection
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:17-alpine")

        private fun expo(
            id: String = EXPO_ID,
            title: String = "박람회",
            description: String = "설명",
            coverImage: String? = "https://example.com/cover.png",
            applicationPerson: Long = 100L,
            yesterdayApplicationPerson: Long = 90L,
        ) = Expo(
            id = id,
            title = title,
            description = description,
            startedDay = "2026-09-22",
            finishedDay = "2026-09-23",
            location = "서울",
            coverImage = coverImage,
            x = "127.123456",
            y = "37.123456",
            applicationPerson = applicationPerson,
            yesterdayApplicationPerson = yesterdayApplicationPerson,
        )

        private fun trainingProgram(
            id: Long? = null,
            title: String = "필수",
            startedAt: String = "2026-09-22T09:00",
            endedAt: String = "2026-09-22T10:00",
            category: Category = Category.ESSENTIAL,
            expo: Expo?,
        ) = TrainingProgram(
            id = id,
            title = title,
            startedAt = startedAt,
            endedAt = endedAt,
            category = category,
            expo = expo,
        )

        private fun standardProgram(
            id: Long? = null,
            title: String = "일반",
            startedAt: String = "2026-09-22T09:00",
            endedAt: String = "2026-09-22T10:00",
            expo: Expo?,
        ) = StandardProgram(
            id = id,
            title = title,
            startedAt = startedAt,
            endedAt = endedAt,
            expo = expo,
        )
    }
}

@TestConfiguration(proxyBeanMethods = false)
@EnableAutoConfiguration
@EntityScan("team.startup.expo.domain")
@EnableJpaRepositories("team.startup.expo.domain")
class PersistenceTestApplication
