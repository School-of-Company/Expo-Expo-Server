package team.startup.expo.domain.expo

import io.kotest.matchers.shouldBe
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.persistence.autoconfigure.EntityScan
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.core.Ordered
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.web.context.RequestAttributeSecurityContextRepository
import org.springframework.web.filter.OncePerRequestFilter
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import team.startup.expo.domain.expo.repository.ExpoRepository
import team.startup.expo.domain.standard.repository.StandardProgramRepository
import team.startup.expo.domain.training.repository.TrainingProgramRepository
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.ObjectNode
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

@SpringBootTest(
    webEnvironment = RANDOM_PORT,
    properties = [
        "eureka.client.enabled=false",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.flyway.enabled=true",
    ],
)
@EntityScan("team.startup.expo.domain")
@Import(HttpTestAuthenticationConfiguration::class)
@Testcontainers
class ExpoApiPostgresHttpTests {
    @LocalServerPort
    private var port: Int = 0

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @Autowired
    private lateinit var expoRepository: ExpoRepository

    @Autowired
    private lateinit var standardProgramRepository: StandardProgramRepository

    @Autowired
    private lateinit var trainingProgramRepository: TrainingProgramRepository

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    private val httpClient = HttpClient.newHttpClient()

    @BeforeEach
    fun clearTables() {
        jdbcTemplate.execute("TRUNCATE TABLE tb_training_program, tb_standard_program, tb_expo RESTART IDENTITY")
    }

    @Test
    fun `실제 HTTP 관리자 요청은 PostgreSQL에 박람회 전체를 생성한다`() {
        val response = postExpo(VALID_REQUEST_JSON, authority = "ROLE_ADMIN")

        response.statusCode() shouldBe 201
        response
            .headers()
            .firstValue(HttpHeaders.CONTENT_TYPE)
            .orElseThrow()
            .startsWith(MediaType.APPLICATION_JSON_VALUE) shouldBe true
        val expoId = objectMapper.readTree(response.body()).get("expoId").asString()
        val expo = expoRepository.findById(expoId).orElseThrow()
        expo.id.matches(Regex(ID_PATTERN)) shouldBe true
        expo.title shouldBe "2026 박람회"
        expo.startedDay shouldBe "2026-09-24"
        expo.finishedDay shouldBe "2026-09-25"
        expo.applicationPerson shouldBe 0L
        expo.yesterdayApplicationPerson shouldBe 0L
        standardProgramRepository.findByExpo(expo).single().let { program ->
            program.title shouldBe "일반 프로그램"
            program.startedAt shouldBe "2026-09-24T09:00"
            program.endedAt shouldBe "2026-09-24T10:00"
            program.expo?.id shouldBe expoId
        }
        trainingProgramRepository.findByExpo(expo).single().let { program ->
            program.title shouldBe "연수 프로그램"
            program.startedAt shouldBe "2026-09-24T10:00"
            program.endedAt shouldBe "2026-09-24T11:00"
            program.category.name shouldBe "ESSENTIAL"
            program.expo?.id shouldBe expoId
        }
    }

    @Test
    fun `실제 HTTP 요청은 검증 인증 권한 상태를 구분한다`() {
        val badRequest = postExpo(VALID_REQUEST_JSON.replace("일반 프로그램", "가".repeat(51)), "ROLE_ADMIN")
        val unauthorized = postExpo(VALID_REQUEST_JSON)
        val forbidden = postExpo(VALID_REQUEST_JSON, authority = "ROLE_USER")

        assertError(badRequest, expectedStatus = 400, expectedMessage = "잘못된 요청입니다.")
        assertError(unauthorized, expectedStatus = 401, expectedMessage = "인증이 필요합니다.")
        assertError(forbidden, expectedStatus = 403, expectedMessage = "접근 권한이 없습니다.")
        expoRepository.count() shouldBe 0L
    }

    @Test
    fun `실제 HTTP 생성은 빈 프로그램 목록을 허용한다`() {
        val emptyProgramsRequest = objectMapper.readTree(VALID_REQUEST_JSON) as ObjectNode
        emptyProgramsRequest.putArray("addStandardProRequestDto")
        emptyProgramsRequest.putArray("addTrainingProRequestDto")

        val response = postExpo(emptyProgramsRequest.toString(), authority = "ROLE_ADMIN")

        response.statusCode() shouldBe 201
        expoRepository.count() shouldBe 1L
        standardProgramRepository.count() shouldBe 0L
        trainingProgramRepository.count() shouldBe 0L
    }

    @Test
    fun `실제 HTTP 목록과 상세 조회는 계약 필드만 반환한다`() {
        val expoId = createExpo()

        val listResponse = request("/expo", "GET")
        val detailResponse = request("/expo/$expoId", "GET")

        listResponse.statusCode() shouldBe 200
        objectMapper.readTree(listResponse.body()).single().let { summary ->
            summary.get("id").asString() shouldBe expoId
            summary.get("title").asString() shouldBe "2026 박람회"
            summary.has("location") shouldBe false
        }
        detailResponse.statusCode() shouldBe 200
        objectMapper.readTree(detailResponse.body()).let { detail ->
            detail.get("title").asString() shouldBe "2026 박람회"
            detail.get("location").asString() shouldBe "서울"
            detail.has("id") shouldBe false
            detail.has("applicationPerson") shouldBe false
        }
    }

    @Test
    fun `실제 HTTP 목록은 빈 배열이고 없는 상세는 404다`() {
        val listResponse = request("/expo", "GET")
        val detailResponse = request("/expo/not-found", "GET")

        listResponse.statusCode() shouldBe 200
        objectMapper.readTree(listResponse.body()).isEmpty shouldBe true
        assertError(detailResponse, expectedStatus = 404, expectedMessage = "박람회를 찾을 수 없습니다.")
    }

    @Test
    fun `실제 HTTP 수정은 기존 프로그램 수정과 신규 추가 및 카운터 보존을 함께 처리한다`() {
        val expoId = createExpo()
        val expo = expoRepository.findById(expoId).orElseThrow()
        expo.plusApplicationPerson()
        expoRepository.saveAndFlush(expo)
        val standardId = standardProgramRepository.findByExpo(expo).single().id!!
        val trainingId = trainingProgramRepository.findByExpo(expo).single().id!!
        val updateRequest = updateRequest(standardId, trainingId)

        val response = request("/expo/$expoId", "PATCH", updateRequest)

        response.statusCode() shouldBe 204
        response.body() shouldBe ""
        expoRepository.findById(expoId).orElseThrow().let { updated ->
            updated.title shouldBe "수정 박람회"
            updated.location shouldBe "부산"
            updated.applicationPerson shouldBe 1L
            updated.yesterdayApplicationPerson shouldBe 0L
            standardProgramRepository.findByExpo(updated).map { it.title }.toSet() shouldBe
                setOf("수정 일반", "신규 일반")
            trainingProgramRepository.findByExpo(updated).map { it.title }.toSet() shouldBe
                setOf("수정 연수", "신규 연수")
        }
    }

    @Test
    fun `실제 HTTP 수정은 기존 프로그램 누락 시 쓰기 전에 409로 거부한다`() {
        val expoId = createExpo()
        val updateRequest = objectMapper.readTree(EMPTY_UPDATE_REQUEST_JSON) as ObjectNode

        val response = request("/expo/$expoId", "PATCH", updateRequest.toString())

        assertError(response, expectedStatus = 409, expectedMessage = "박람회 프로그램 정보가 충돌합니다.")
        expoRepository.findById(expoId).orElseThrow().title shouldBe "2026 박람회"
        standardProgramRepository.count() shouldBe 1L
        trainingProgramRepository.count() shouldBe 1L
    }

    @Test
    fun `실제 HTTP 수정은 중복 타 박람회 미존재 프로그램 ID를 409로 거부한다`() {
        val targetExpoId = createExpo()
        val targetExpo = expoRepository.findById(targetExpoId).orElseThrow()
        val targetStandardId = standardProgramRepository.findByExpo(targetExpo).single().id!!
        val targetTrainingId = trainingProgramRepository.findByExpo(targetExpo).single().id!!
        val otherExpoId = createExpo()
        val otherExpo = expoRepository.findById(otherExpoId).orElseThrow()
        val otherStandardId = standardProgramRepository.findByExpo(otherExpo).single().id!!

        val duplicateIdRequest = objectMapper.readTree(updateRequest(targetStandardId, targetTrainingId)) as ObjectNode
        duplicateIdRequest.withArray("updateStandardProRequestDto").add(
            duplicateIdRequest.withArray("updateStandardProRequestDto").first().deepCopy(),
        )
        val foreignIdRequest = updateRequest(otherStandardId, targetTrainingId)
        val missingIdRequest = updateRequest(999_999L, targetTrainingId)

        listOf(duplicateIdRequest.toString(), foreignIdRequest, missingIdRequest).forEach { payload ->
            val response = request("/expo/$targetExpoId", "PATCH", payload)

            assertError(response, expectedStatus = 409, expectedMessage = "박람회 프로그램 정보가 충돌합니다.")
            expoRepository.findById(targetExpoId).orElseThrow().title shouldBe "2026 박람회"
        }
    }

    @Test
    fun `실제 HTTP 수정은 없는 박람회를 404로 응답한다`() {
        val response = request("/expo/not-found", "PATCH", EMPTY_UPDATE_REQUEST_JSON)

        assertError(response, expectedStatus = 404, expectedMessage = "박람회를 찾을 수 없습니다.")
    }

    @Test
    fun `실제 HTTP 조회와 수정도 관리자 권한을 요구한다`() {
        val unauthorizedList = request("/expo", "GET", authority = null)
        val forbiddenUpdate = request("/expo/not-found", "PATCH", EMPTY_UPDATE_REQUEST_JSON, authority = "ROLE_USER")

        assertError(unauthorizedList, expectedStatus = 401, expectedMessage = "인증이 필요합니다.")
        assertError(forbiddenUpdate, expectedStatus = 403, expectedMessage = "접근 권한이 없습니다.")
    }

    @Test
    fun `실제 HTTP 수정 중 Training 저장 실패는 모든 테이블을 요청 전 상태로 롤백한다`() {
        val expoId = createExpo()
        val expo = expoRepository.findById(expoId).orElseThrow()
        val standardId = standardProgramRepository.findByExpo(expo).single().id!!
        val trainingId = trainingProgramRepository.findByExpo(expo).single().id!!
        val before = databaseSnapshot(expoId)

        withTrainingWriteFailure {
            val payload = updateRequest(standardId, trainingId).replace("수정 연수", "롤백 연수")
            val response = request("/expo/$expoId", "PATCH", payload)

            assertError(response, expectedStatus = 500, expectedMessage = "서버 오류가 발생했습니다.")
            databaseSnapshot(expoId) shouldBe before
        }
    }

    @Test
    fun `실제 HTTP 생성 중 Training 저장 실패는 부모와 다른 자식 저장까지 롤백한다`() {
        withTrainingWriteFailure {
            val payload = VALID_REQUEST_JSON.replace("연수 프로그램", "롤백 연수")
            val response = postExpo(payload, authority = "ROLE_ADMIN")

            assertError(response, expectedStatus = 500, expectedMessage = "서버 오류가 발생했습니다.")
            expoRepository.count() shouldBe 0L
            standardProgramRepository.count() shouldBe 0L
            trainingProgramRepository.count() shouldBe 0L
        }
    }

    @Test
    fun `독립 트랜잭션의 카운터 변경과 실제 HTTP 수정이 겹쳐도 두 카운터를 보존한다`() {
        val expoId = createExpo()
        val expo = expoRepository.findById(expoId).orElseThrow()
        val standardId = standardProgramRepository.findByExpo(expo).single().id!!
        val trainingId = trainingProgramRepository.findByExpo(expo).single().id!!
        val counterConnection = postgres.createConnection("")
        counterConnection.autoCommit = false
        val patchFuture: CompletableFuture<HttpResponse<String>>

        try {
            counterConnection
                .prepareStatement(
                    "UPDATE tb_expo SET application_person = application_person + 1, " +
                        "yesterday_application_person = yesterday_application_person + 1 WHERE id = ?",
                ).use { statement ->
                    statement.setString(1, expoId)
                    statement.executeUpdate() shouldBe 1
                }
            patchFuture =
                CompletableFuture.supplyAsync {
                    request("/expo/$expoId", "PATCH", updateRequest(standardId, trainingId))
                }

            awaitPatchRowLock() shouldBe true
            counterConnection.commit()
            patchFuture.get(10, TimeUnit.SECONDS).statusCode() shouldBe 204
        } finally {
            if (!counterConnection.autoCommit) {
                counterConnection.rollback()
            }
            counterConnection.close()
        }

        expoRepository.findById(expoId).orElseThrow().let { updated ->
            updated.title shouldBe "수정 박람회"
            updated.location shouldBe "부산"
            updated.applicationPerson shouldBe 1L
            updated.yesterdayApplicationPerson shouldBe 1L
        }
    }

    private fun postExpo(
        body: String,
        authority: String? = null,
    ): HttpResponse<String> = request("/expo", "POST", body, authority)

    private fun request(
        path: String,
        method: String,
        body: String? = null,
        authority: String? = "ROLE_ADMIN",
    ): HttpResponse<String> {
        val request =
            HttpRequest
                .newBuilder(URI.create("http://localhost:$port$path"))
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .apply {
                    authority?.let { header(TEST_AUTHORITY_HEADER, it) }
                }.method(
                    method,
                    body?.let(HttpRequest.BodyPublishers::ofString) ?: HttpRequest.BodyPublishers.noBody(),
                ).build()

        return httpClient.send(request, HttpResponse.BodyHandlers.ofString())
    }

    private fun createExpo(): String {
        val response = postExpo(VALID_REQUEST_JSON, authority = "ROLE_ADMIN")
        response.statusCode() shouldBe 201
        return objectMapper.readTree(response.body()).get("expoId").asString()
    }

    private fun databaseSnapshot(expoId: String): Map<String, List<Map<String, Any?>>> =
        mapOf(
            "expo" to jdbcTemplate.queryForList("SELECT * FROM tb_expo WHERE id = ?", expoId),
            "standard" to jdbcTemplate.queryForList("SELECT * FROM tb_standard_program WHERE expo_id = ? ORDER BY id", expoId),
            "training" to jdbcTemplate.queryForList("SELECT * FROM tb_training_program WHERE expo_id = ? ORDER BY id", expoId),
        )

    private fun withTrainingWriteFailure(block: () -> Unit) {
        try {
            jdbcTemplate.execute(
                """
                CREATE FUNCTION fail_training_write() RETURNS trigger AS ${'$'}trigger${'$'}
                BEGIN
                    IF NEW.title = '롤백 연수' THEN
                        RAISE EXCEPTION 'forced training failure';
                    END IF;
                    RETURN NEW;
                END;
                ${'$'}trigger${'$'} LANGUAGE plpgsql
                """.trimIndent(),
            )
            jdbcTemplate.execute(
                "CREATE TRIGGER fail_training_write BEFORE INSERT OR UPDATE ON tb_training_program " +
                    "FOR EACH ROW EXECUTE FUNCTION fail_training_write()",
            )
            block()
        } finally {
            jdbcTemplate.execute("DROP TRIGGER IF EXISTS fail_training_write ON tb_training_program")
            jdbcTemplate.execute("DROP FUNCTION IF EXISTS fail_training_write()")
        }
    }

    private fun awaitPatchRowLock(): Boolean {
        repeat(100) {
            val waiting =
                jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM pg_stat_activity WHERE wait_event_type = 'Lock' AND query ILIKE 'update tb_expo%'",
                    Long::class.java,
                ) ?: 0L
            if (waiting > 0L) {
                return true
            }
            Thread.sleep(20)
        }
        return false
    }

    private fun updateRequest(
        standardId: Long,
        trainingId: Long,
    ): String =
        """
        {
          "title": "수정 박람회",
          "description": "수정 설명",
          "startedDay": "2026-09-24",
          "finishedDay": "2026-09-26",
          "location": "부산",
          "coverImage": "https://example.com/updated.png",
          "x": "129.075",
          "y": "35.179",
          "updateStandardProRequestDto": [
            {"id": $standardId, "title": "수정 일반", "startedAt": "2026-09-24 09:30", "endedAt": "2026-09-24 10:30"},
            {"title": "신규 일반", "startedAt": "2026-09-25 09:00", "endedAt": "2026-09-25 10:00"}
          ],
          "updateTrainingProRequestDto": [
            {"id": $trainingId, "title": "수정 연수", "startedAt": "2026-09-24 10:30", "endedAt": "2026-09-24 11:30", "category": "CHOICE"},
            {"title": "신규 연수", "startedAt": "2026-09-25 10:00", "endedAt": "2026-09-25 11:00", "category": "ESSENTIAL"}
          ]
        }
        """.trimIndent()

    private fun assertError(
        response: HttpResponse<String>,
        expectedStatus: Int,
        expectedMessage: String,
    ) {
        response.statusCode() shouldBe expectedStatus
        objectMapper.readTree(response.body()).let { body ->
            body.get("status").asInt() shouldBe expectedStatus
            body.get("message").asString() shouldBe expectedMessage
        }
    }

    companion object {
        private const val TEST_AUTHORITY_HEADER = "X-Test-Authority"
        private const val ID_PATTERN = "^[0-9a-f]{12}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{8}$"
        private const val STANDARD_PROGRAM_JSON =
            """{
                  "title": "일반 프로그램",
                  "startedAt": "2026-09-24 09:00",
                  "endedAt": "2026-09-24 10:00"
                }"""
        private const val TRAINING_PROGRAM_JSON =
            """{
                  "title": "연수 프로그램",
                  "startedAt": "2026-09-24 10:00",
                  "endedAt": "2026-09-24 11:00",
                  "category": "ESSENTIAL"
                }"""

        @Container
        @ServiceConnection
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:17-alpine")

        private val VALID_REQUEST_JSON =
            """
            {
              "title": "2026 박람회",
              "description": "설명",
              "startedDay": "2026-09-24",
              "finishedDay": "2026-09-25",
              "location": "서울",
              "coverImage": "https://example.com/cover.png",
              "x": "127.123",
              "y": "37.456",
              "addStandardProRequestDto": [$STANDARD_PROGRAM_JSON],
              "addTrainingProRequestDto": [$TRAINING_PROGRAM_JSON]
            }
            """.trimIndent()

        private val EMPTY_UPDATE_REQUEST_JSON =
            """
            {
              "title": "거부될 수정",
              "description": "설명",
              "startedDay": "2026-09-24",
              "finishedDay": "2026-09-25",
              "location": "서울",
              "coverImage": "https://example.com/cover.png",
              "x": "127.123",
              "y": "37.456",
              "updateStandardProRequestDto": [],
              "updateTrainingProRequestDto": []
            }
            """.trimIndent()
    }
}

@TestConfiguration(proxyBeanMethods = false)
class HttpTestAuthenticationConfiguration {
    @Bean
    fun httpTestAuthenticationFilter(): FilterRegistrationBean<OncePerRequestFilter> {
        val filter =
            object : OncePerRequestFilter() {
                override fun doFilterInternal(
                    request: HttpServletRequest,
                    response: HttpServletResponse,
                    filterChain: FilterChain,
                ) {
                    request.getHeader("X-Test-Authority")?.let { authority ->
                        val context = SecurityContextHolder.createEmptyContext()
                        context.authentication =
                            UsernamePasswordAuthenticationToken.authenticated(
                                "http-test-user",
                                null,
                                listOf(SimpleGrantedAuthority(authority)),
                            )
                        request.setAttribute(RequestAttributeSecurityContextRepository.DEFAULT_REQUEST_ATTR_NAME, context)
                    }
                    filterChain.doFilter(request, response)
                }
            }

        return FilterRegistrationBean<OncePerRequestFilter>(filter).apply {
            order = Ordered.HIGHEST_PRECEDENCE
        }
    }
}
