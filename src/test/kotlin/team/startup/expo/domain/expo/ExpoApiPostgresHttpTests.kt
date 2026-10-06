package team.startup.expo.domain.expo

import com.sun.net.httpserver.HttpServer
import io.kotest.matchers.collections.shouldBeIn
import io.kotest.matchers.shouldBe
import jakarta.persistence.EntityManagerFactory
import org.hibernate.SessionFactory
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.persistence.autoconfigure.EntityScan
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.util.ReflectionTestUtils
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import team.startup.expo.domain.expo.repository.ExpoRepository
import team.startup.expo.domain.expo.service.impl.GetExpoValidationServiceImpl
import team.startup.expo.domain.standard.entity.StandardProgram
import team.startup.expo.domain.standard.repository.StandardProgramRepository
import team.startup.expo.domain.training.entity.Category
import team.startup.expo.domain.training.entity.TrainingProgram
import team.startup.expo.domain.training.repository.TrainingProgramRepository
import team.startup.expo.support.TestJwt
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.ObjectNode
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@SpringBootTest(
    webEnvironment = RANDOM_PORT,
    properties = [
        "eureka.client.enabled=false",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.jpa.properties.hibernate.generate_statistics=true",
        "spring.flyway.enabled=true",
        "EXPO_INTERNAL_TOKEN=test-internal-token",
    ],
)
@EntityScan("team.startup.expo.domain")
@Testcontainers
class ExpoApiPostgresHttpTests {
    @LocalServerPort
    private var port: Int = 0

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @Autowired
    private lateinit var expoRepository: ExpoRepository

    @Autowired
    private lateinit var validationService: GetExpoValidationServiceImpl

    @Autowired
    private lateinit var standardProgramRepository: StandardProgramRepository

    @Autowired
    private lateinit var trainingProgramRepository: TrainingProgramRepository

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    @Autowired
    private lateinit var entityManagerFactory: EntityManagerFactory

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
    fun `X-User-Id만으로는 관리자 인증이 되지 않는다`() {
        val response =
            httpClient.send(
                HttpRequest
                    .newBuilder(URI.create("http://localhost:$port/expo"))
                    .header("X-User-Id", "1")
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.ofString(),
            )
        assertError(response, expectedStatus = 401, expectedMessage = "인증이 필요합니다.")
    }

    @Test
    fun `변조된 Bearer 토큰은 거부한다`() {
        val response =
            httpClient.send(
                HttpRequest
                    .newBuilder(URI.create("http://localhost:$port/expo"))
                    .header(HttpHeaders.AUTHORIZATION, "Bearer ${TestJwt.token("ROLE_ADMIN")}x")
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.ofString(),
            )
        assertError(response, expectedStatus = 401, expectedMessage = "인증이 필요합니다.")
    }

    @Test
    fun `만료됐거나 15분보다 긴 Bearer 토큰은 거부한다`() {
        listOf(
            TestJwt.token("ROLE_ADMIN", ttlSeconds = 899, issuedAtOffsetSeconds = -900),
            TestJwt.token("ROLE_ADMIN", ttlSeconds = 1800),
        ).forEach { token ->
            val response =
                httpClient.send(
                    HttpRequest
                        .newBuilder(URI.create("http://localhost:$port/expo"))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer $token")
                        .GET()
                        .build(),
                    HttpResponse.BodyHandlers.ofString(),
                )
            assertError(response, expectedStatus = 401, expectedMessage = "인증이 필요합니다.")
        }
    }

    @Test
    fun `Spring 기본 예외는 원래 상태 코드를 유지하고 health는 인증 없이 열린다`() {
        val unsupportedMediaType =
            httpClient.send(
                HttpRequest
                    .newBuilder(URI.create("http://localhost:$port/expo"))
                    .header(HttpHeaders.CONTENT_TYPE, MediaType.TEXT_PLAIN_VALUE)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer ${TestJwt.token("ROLE_ADMIN")}")
                    .POST(HttpRequest.BodyPublishers.ofString(VALID_REQUEST_JSON))
                    .build(),
                HttpResponse.BodyHandlers.ofString(),
            )
        val health = request("/actuator/health", "GET", authority = null)

        assertError(unsupportedMediaType, expectedStatus = 415, expectedMessage = "요청을 처리할 수 없습니다.")
        // 보안 통과 여부만 본다. 503은 Redis 등 외부 의존성이 DOWN인 환경(CI)에서의 정상 응답이다
        health.statusCode() shouldBeIn listOf(200, 503)
    }

    @Test
    fun `실제 HTTP 목록은 최신 박람회부터 반환한다`() {
        val olderId = createExpo()
        val newerId = createExpo()

        val listResponse = request("/expo", "GET")

        objectMapper.readTree(listResponse.body()).toList().map { it.get("id").asString() } shouldBe listOf(newerId, olderId)
    }

    @Test
    fun `실제 HTTP valid는 Form 서비스 상태와 기존 JSON 키를 반환한다`() {
        val expoId = createExpo()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val serverExecutor = Executors.newCachedThreadPool()
        val activeRequests = AtomicInteger()
        val maxActiveRequests = AtomicInteger()
        server.executor = serverExecutor
        server.createContext("/") { exchange ->
            val active = activeRequests.incrementAndGet()
            maxActiveRequests.updateAndGet { maxOf(it, active) }
            Thread.sleep(100)
            val foundForm =
                exchange.requestURI.path == "/forms/$expoId" &&
                    exchange.requestURI.query in
                    setOf("type=STANDARD&applicationType=PRE", "type=TRAINEE&applicationType=FIELD")
            val foundSurvey = exchange.requestURI.path == "/surveys/$expoId" && exchange.requestURI.query == "type=STANDARD"
            val found = foundForm || foundSurvey
            exchange.sendResponseHeaders(if (found) 200 else 404, -1)
            exchange.close()
            activeRequests.decrementAndGet()
        }
        server.start()
        try {
            ReflectionTestUtils.setField(validationService, "formServiceUrl", "http://127.0.0.1:${server.address.port}")
            val response = request("/expo/valid", "GET")
            response.statusCode() shouldBe 200
            objectMapper.readTree(response.body()) shouldBe
                objectMapper.readTree(
                    """{"expoValid":[{"expoId":"$expoId","preStandardFormCreatedStatus":true,"siteStandardFormCreatedStatus":false,"traineeFormCreatedStatus":true,"StandardSurveyCreatedStatus":true,"traineeSurveyCreatedStatus":false}]}""",
                )
            (maxActiveRequests.get() > 1) shouldBe true
        } finally {
            server.stop(0)
            serverExecutor.shutdown()
            ReflectionTestUtils.setField(validationService, "formServiceUrl", "")
        }
    }

    @Test
    fun `Form 서비스 URL 누락 시 valid는 빈 상태를 가장하지 않는다`() {
        createExpo()

        assertError(request("/expo/valid", "GET"), 503, "Form 서비스 연결이 설정되지 않았습니다.")
    }

    @Test
    fun `페이지 목록은 전체 건수와 정렬된 일부 결과를 반환한다`() {
        val ids = (1..5).map { createExpo() }

        val first = objectMapper.readTree(request("/expo?page=0&size=2", "GET").body())
        val last = objectMapper.readTree(request("/expo?page=2&size=2", "GET").body())
        val pastEnd = objectMapper.readTree(request("/expo?page=3&size=2", "GET").body())

        first.get("content").toList().map { it.get("id").asString() } shouldBe ids.sortedDescending().take(2)
        first.get("totalElements").asLong() shouldBe 5L
        first.get("totalPages").asInt() shouldBe 3
        first.get("hasNext").asBoolean() shouldBe true
        last.get("content").size() shouldBe 1
        last.get("hasNext").asBoolean() shouldBe false
        pastEnd.get("content").isEmpty shouldBe true
        pastEnd.get("totalElements").asLong() shouldBe 5L
    }

    @Test
    fun `페이지 기본값 빈 결과 입력 오류와 권한을 구분한다`() {
        val empty = objectMapper.readTree(request("/expo?size=2", "GET").body())
        empty.get("page").asInt() shouldBe 0
        empty.get("size").asInt() shouldBe 2
        empty.get("totalPages").asInt() shouldBe 0
        empty.get("content").isEmpty shouldBe true

        listOf("page=-1", "size=0", "size=101", "page=abc", "page=", "size=").forEach { query ->
            assertError(request("/expo?$query", "GET"), 400, "잘못된 요청입니다.")
        }
        assertError(request("/expo?page=0", "GET", authority = null), 401, "인증이 필요합니다.")
        assertError(request("/expo?page=0", "GET", authority = "ROLE_USER"), 403, "접근 권한이 없습니다.")
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
    fun `내부 박람회 조회는 공유 토큰으로 날짜만 반환하고 없는 박람회는 404다`() {
        val expoId = createExpo()

        val response = request("/internal/expo/$expoId", "GET", authority = null, internalToken = "test-internal-token")
        val period = objectMapper.readTree(response.body())

        response.statusCode() shouldBe 200
        period.size() shouldBe 2
        period.get("startedDay").asString() shouldBe "2026-09-24"
        period.get("finishedDay").asString() shouldBe "2026-09-25"
        assertError(
            request("/internal/expo/not-found", "GET", authority = null, internalToken = "test-internal-token"),
            expectedStatus = 404,
            expectedMessage = "박람회를 찾을 수 없습니다.",
        )
    }

    @Test
    fun `내부 박람회 조회는 토큰 누락과 불일치를 거부하고 외부 관리자 권한을 우회하지 못한다`() {
        val expoId = createExpo()

        assertError(request("/internal/expo/$expoId", "GET"), 401, "인증이 필요합니다.")
        assertError(request("/internal/expo/$expoId", "GET", internalToken = "wrong-token"), 401, "인증이 필요합니다.")
        assertError(request("/internal/expo/$expoId/", "GET", authority = null), 401, "인증이 필요합니다.")
        assertError(request("/internal/expo/$expoId", "GET", authority = "ROLE_ADMIN"), 401, "인증이 필요합니다.")
        assertError(request("/expo/$expoId", "GET", authority = null, internalToken = "test-internal-token"), 401, "인증이 필요합니다.")
        request("/expo/$expoId", "GET").statusCode() shouldBe 200
    }

    @Test
    fun `실제 HTTP 프로그램 목록은 Expo별 ID순으로 계약 필드만 반환한다`() {
        val expoId = createExpo()
        createExpo()
        val expo = expoRepository.findById(expoId).orElseThrow()
        val standardId = standardProgramRepository.findByExpo(expo).single().id!!
        val trainingId = trainingProgramRepository.findByExpo(expo).single().id!!
        val secondStandardId =
            standardProgramRepository
                .saveAndFlush(
                    StandardProgram(
                        title = "두 번째 일반",
                        startedAt = "2026-09-24 12:00",
                        endedAt = "2026-09-24 13:00",
                        expo = expo,
                    ),
                ).id!!
        val secondTrainingId =
            trainingProgramRepository
                .saveAndFlush(
                    TrainingProgram(
                        title = "두 번째 연수",
                        startedAt = "2026-09-24 13:00",
                        endedAt = "2026-09-24 14:00",
                        category = Category.CHOICE,
                        expo = expo,
                    ),
                ).id!!

        val statistics = entityManagerFactory.unwrap(SessionFactory::class.java).statistics
        statistics.clear()
        val standardResponse = request("/standard/program/$expoId", "GET")
        statistics.prepareStatementCount shouldBe 2L
        statistics.clear()
        val trainingResponse = request("/training/program/$expoId", "GET")
        statistics.prepareStatementCount shouldBe 2L

        standardResponse.statusCode() shouldBe 200
        trainingResponse.statusCode() shouldBe 200
        objectMapper.readTree(standardResponse.body()) shouldBe
            objectMapper.readTree(
                """[
                    {"id":$standardId,"title":"일반 프로그램","startedAt":"2026-09-24T09:00","endedAt":"2026-09-24T10:00"},
                    {"id":$secondStandardId,"title":"두 번째 일반","startedAt":"2026-09-24 12:00","endedAt":"2026-09-24 13:00"}
                ]""",
            )
        objectMapper.readTree(trainingResponse.body()) shouldBe
            objectMapper.readTree(
                """[
                    {"id":$trainingId,"title":"연수 프로그램","startedAt":"2026-09-24T10:00","endedAt":"2026-09-24T11:00","category":"ESSENTIAL"},
                    {"id":$secondTrainingId,"title":"두 번째 연수","startedAt":"2026-09-24 13:00","endedAt":"2026-09-24 14:00","category":"CHOICE"}
                ]""",
            )
    }

    @Test
    fun `실제 HTTP 프로그램 조회는 빈 목록과 없는 Expo 및 권한 실패를 구분한다`() {
        val emptyRequest = objectMapper.readTree(VALID_REQUEST_JSON) as ObjectNode
        emptyRequest.putArray("addStandardProRequestDto")
        emptyRequest.putArray("addTrainingProRequestDto")
        val expoId = objectMapper.readTree(postExpo(emptyRequest.toString(), "ROLE_ADMIN").body()).get("expoId").asString()

        for (path in listOf("/standard/program", "/training/program")) {
            val empty = request("$path/$expoId", "GET")
            empty.statusCode() shouldBe 200
            objectMapper.readTree(empty.body()).isEmpty shouldBe true
            assertError(request("$path/not-found", "GET"), 404, "박람회를 찾을 수 없습니다.")
            assertError(request("$path/$expoId", "GET", authority = null), 401, "인증이 필요합니다.")
            assertError(request("$path/$expoId", "GET", authority = "ROLE_USER"), 403, "접근 권한이 없습니다.")
        }
    }

    @Test
    fun `실제 HTTP 프로그램 조회 ID로 수정한 뒤 신규 ID와 변경 값을 다시 조회한다`() {
        val expoId = createExpo()
        val standardId =
            objectMapper
                .readTree(request("/standard/program/$expoId", "GET").body())
                .single()
                .get("id")
                .asLong()
        val trainingId =
            objectMapper
                .readTree(request("/training/program/$expoId", "GET").body())
                .single()
                .get("id")
                .asLong()

        request("/expo/$expoId", "PATCH", updateRequest(standardId, trainingId)).statusCode() shouldBe 204

        val standardPrograms = objectMapper.readTree(request("/standard/program/$expoId", "GET").body())
        val trainingPrograms = objectMapper.readTree(request("/training/program/$expoId", "GET").body())
        standardPrograms.size() shouldBe 2
        standardPrograms[0].get("id").asLong() shouldBe standardId
        standardPrograms[0].get("title").asString() shouldBe "수정 일반"
        standardPrograms[1].get("id").asLong() shouldBe (standardId + 1)
        standardPrograms[1].get("title").asString() shouldBe "신규 일반"
        trainingPrograms.size() shouldBe 2
        trainingPrograms[0].get("id").asLong() shouldBe trainingId
        trainingPrograms[0].get("category").asString() shouldBe "CHOICE"
        trainingPrograms[1].get("id").asLong() shouldBe (trainingId + 1)
        trainingPrograms[1].get("title").asString() shouldBe "신규 연수"
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
    fun `실제 HTTP 수정은 누락된 기존 프로그램을 삭제한다`() {
        val expoId = createExpo()
        val updateRequest = objectMapper.readTree(EMPTY_UPDATE_REQUEST_JSON) as ObjectNode

        val response = request("/expo/$expoId", "PATCH", updateRequest.toString())

        response.statusCode() shouldBe 204
        expoRepository.findById(expoId).orElseThrow().title shouldBe "거부될 수정"
        standardProgramRepository.count() shouldBe 0L
        trainingProgramRepository.count() shouldBe 0L
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
        internalToken: String? = null,
    ): HttpResponse<String> {
        val request =
            HttpRequest
                .newBuilder(URI.create("http://localhost:$port$path"))
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .apply {
                    authority?.let { header(HttpHeaders.AUTHORIZATION, "Bearer ${TestJwt.token(it)}") }
                    internalToken?.let { header("X-Internal-Token", it) }
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
        @JvmStatic
        @DynamicPropertySource
        fun jwtPublicKey(registry: DynamicPropertyRegistry) {
            registry.add("JWT_PUBLIC_KEY") { TestJwt.publicKeyPem }
        }

        private const val ID_PATTERN = "^[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$"
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
