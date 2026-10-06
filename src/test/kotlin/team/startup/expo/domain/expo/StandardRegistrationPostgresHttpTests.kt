package team.startup.expo.domain.expo

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.persistence.autoconfigure.EntityScan
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import team.startup.expo.support.TestJwt
import tools.jackson.databind.ObjectMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@SpringBootTest(
    webEnvironment = RANDOM_PORT,
    properties = [
        "eureka.client.enabled=false",
        "spring.jpa.hibernate.ddl-auto=validate",
        "EXPO_INTERNAL_TOKEN=test-internal-token",
    ],
)
@EntityScan("team.startup.expo.domain")
@Testcontainers
class StandardRegistrationPostgresHttpTests {
    @LocalServerPort
    private var port: Int = 0

    @Autowired
    private lateinit var jdbc: JdbcTemplate

    @Autowired
    private lateinit var mapper: ObjectMapper

    private val http = HttpClient.newHttpClient()

    @BeforeEach
    fun setUp() {
        jdbc.execute("TRUNCATE TABLE tb_expo_image, tb_training_program, tb_standard_program, tb_expo RESTART IDENTITY CASCADE")
        listOf(EXPO_ID, OTHER_EXPO_ID).forEach { id ->
            jdbc.update(
                """INSERT INTO tb_expo (id,title,description,started_day,finished_day,location,x,y,application_person,yesterday_application_person)
                   VALUES (?,'박람회','설명','2026-09-24','2026-09-25','서울','127','37',5,3)""",
                id,
            )
        }
    }

    @Test
    fun `최초 호출만 인원을 늘리고 재호출과 다른 참가자는 멱등하게 집계한다`() {
        put(EXPO_ID, "42").let {
            it.statusCode() shouldBe 204
            it.body() shouldBe ""
        }
        count(EXPO_ID) shouldBe 6L
        put(EXPO_ID, "42").statusCode() shouldBe 204
        count(EXPO_ID) shouldBe 6L
        put(EXPO_ID, "43").statusCode() shouldBe 204
        count(EXPO_ID) shouldBe 7L
        put(OTHER_EXPO_ID, "42").statusCode() shouldBe 204
        count(OTHER_EXPO_ID) shouldBe 6L
        ledger(EXPO_ID) shouldBe listOf(42L, 43L)
        jdbc.queryForObject("SELECT yesterday_application_person FROM tb_expo WHERE id = ?", Long::class.java, EXPO_ID) shouldBe 3L
    }

    @Test
    fun `같은 참가자 동시 PUT은 한 번만 증가하고 다른 참가자 동시 PUT은 모두 증가한다`() {
        concurrently(List(16) { "42" }).forEach { it shouldBe 204 }
        count(EXPO_ID) shouldBe 6L
        ledger(EXPO_ID) shouldBe listOf(42L)

        concurrently((100L..115L).map { it.toString() }).forEach { it shouldBe 204 }
        count(EXPO_ID) shouldBe 22L
        ledger(EXPO_ID).size shouldBe 17
    }

    @Test
    fun `인원 증가 실패는 원장 기록까지 롤백하고 재시도하면 한 번만 집계한다`() {
        try {
            jdbc.execute(
                """
                CREATE FUNCTION fail_expo_count() RETURNS trigger AS ${'$'}trigger${'$'}
                BEGIN
                    RAISE EXCEPTION 'forced count failure';
                END;
                ${'$'}trigger${'$'} LANGUAGE plpgsql
                """.trimIndent(),
            )
            jdbc.execute("CREATE TRIGGER fail_expo_count BEFORE UPDATE ON tb_expo FOR EACH ROW EXECUTE FUNCTION fail_expo_count()")
            assertError(put(EXPO_ID, "42"), 500, "서버 오류가 발생했습니다.")
        } finally {
            jdbc.execute("DROP TRIGGER IF EXISTS fail_expo_count ON tb_expo")
            jdbc.execute("DROP FUNCTION IF EXISTS fail_expo_count()")
        }
        count(EXPO_ID) shouldBe 5L
        ledger(EXPO_ID) shouldBe emptyList()

        put(EXPO_ID, "42").statusCode() shouldBe 204
        count(EXPO_ID) shouldBe 6L
    }

    @Test
    fun `없는 박람회는 404 잘못된 ID는 400 삭제 중인 박람회는 409다`() {
        assertError(put("01900000-0000-7000-8000-000000000099", "42"), 404, "박람회를 찾지 못 했습니다.")
        assertError(put(EXPO_ID, "abc"), 400, "잘못된 요청입니다.")
        assertError(put(EXPO_ID, "0"), 400, "잘못된 요청입니다.")
        assertError(put(EXPO_ID, "-1"), 400, "잘못된 요청입니다.")
        assertError(put(EXPO_ID, "99999999999999999999"), 400, "잘못된 요청입니다.")
        assertError(put("not-a-uuid", "42"), 400, "잘못된 요청입니다.")
        assertError(put(EXPO_ID.uppercase(), "42"), 400, "잘못된 요청입니다.")

        jdbc.update("UPDATE tb_expo SET deleting_at = now() WHERE id = ?", OTHER_EXPO_ID)
        assertError(put(OTHER_EXPO_ID, "42"), 409, "삭제 중인 박람회입니다.")

        count(EXPO_ID) shouldBe 5L
        count(OTHER_EXPO_ID) shouldBe 5L
        jdbc.queryForObject("SELECT COUNT(*) FROM tb_standard_registration", Long::class.java) shouldBe 0L
    }

    @Test
    fun `내부 토큰 누락 불일치와 관리자 JWT만으로는 집계하지 않는다`() {
        assertError(put(EXPO_ID, "42", token = null), 401, "인증이 필요합니다.")
        assertError(put(EXPO_ID, "42", token = "wrong-token"), 401, "인증이 필요합니다.")
        assertError(put(EXPO_ID, "42", token = null, bearer = TestJwt.token("ROLE_ADMIN")), 401, "인증이 필요합니다.")

        count(EXPO_ID) shouldBe 5L
        ledger(EXPO_ID) shouldBe emptyList()
    }

    @Test
    fun `박람회 행이 삭제되면 원장도 함께 정리된다`() {
        put(EXPO_ID, "42").statusCode() shouldBe 204

        jdbc.update("DELETE FROM tb_expo WHERE id = ?", EXPO_ID)

        jdbc.queryForObject("SELECT COUNT(*) FROM tb_standard_registration", Long::class.java) shouldBe 0L
    }

    private fun concurrently(participantIds: List<String>): List<Int> {
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(participantIds.size)
        try {
            val futures =
                participantIds.map { id ->
                    CompletableFuture.supplyAsync({
                        start.await()
                        put(EXPO_ID, id).statusCode()
                    }, executor)
                }
            start.countDown()
            return futures.map { it.get(30, TimeUnit.SECONDS) }
        } finally {
            executor.shutdownNow()
        }
    }

    private fun put(
        expoId: String,
        participantId: String,
        token: String? = "test-internal-token",
        bearer: String? = null,
    ): HttpResponse<String> {
        val request =
            HttpRequest
                .newBuilder(URI.create("http://localhost:$port/internal/expo/$expoId/standard-registrations/$participantId"))
                .apply {
                    token?.let { header("X-Internal-Token", it) }
                    bearer?.let { header("Authorization", "Bearer $it") }
                }.PUT(HttpRequest.BodyPublishers.noBody())
                .build()
        return http.send(request, HttpResponse.BodyHandlers.ofString())
    }

    private fun count(expoId: String): Long? =
        jdbc.queryForObject("SELECT application_person FROM tb_expo WHERE id = ?", Long::class.java, expoId)

    private fun ledger(expoId: String): List<Long?> =
        jdbc.queryForList(
            "SELECT participant_id FROM tb_standard_registration WHERE expo_id = ? ORDER BY participant_id",
            Long::class.java,
            expoId,
        )

    private fun assertError(
        response: HttpResponse<String>,
        status: Int,
        message: String,
    ) {
        response.statusCode() shouldBe status
        mapper.readTree(response.body()).let { body ->
            body.get("status").asInt() shouldBe status
            body.get("message").asString() shouldBe message
        }
    }

    companion object {
        private const val EXPO_ID = "0190abcd-0000-7000-8000-000000000001"
        private const val OTHER_EXPO_ID = "0190abcd-0000-7000-8000-000000000002"

        @JvmStatic
        @DynamicPropertySource
        fun jwtPublicKey(registry: DynamicPropertyRegistry) {
            registry.add("JWT_PUBLIC_KEY") { TestJwt.publicKeyPem }
        }

        @Container
        @ServiceConnection
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:17-alpine")
    }
}
