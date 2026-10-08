package team.startup.expo.domain.expo

import com.sun.net.httpserver.HttpServer
import io.kotest.matchers.collections.shouldBeIn
import io.kotest.matchers.shouldBe
import jakarta.persistence.EntityManagerFactory
import org.hibernate.SessionFactory
import org.junit.jupiter.api.AfterAll
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
import team.startup.expo.domain.expo.service.ExpoDeletionClient
import team.startup.expo.domain.expo.service.impl.GetExpoValidationServiceImpl
import team.startup.expo.domain.image.entity.ExpoImage
import team.startup.expo.domain.image.repository.ExpoImageRepository
import team.startup.expo.domain.image.service.ImageCleanupService
import team.startup.expo.domain.image.storage.impl.LocalImageStorage
import team.startup.expo.domain.standard.entity.StandardProgram
import team.startup.expo.domain.standard.repository.StandardProgramRepository
import team.startup.expo.domain.training.entity.Category
import team.startup.expo.domain.training.entity.TrainingProgram
import team.startup.expo.domain.training.repository.TrainingProgramRepository
import team.startup.expo.support.TestJwt
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.ObjectNode
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.imageio.ImageIO

@SpringBootTest(
    webEnvironment = RANDOM_PORT,
    properties = [
        "eureka.client.enabled=false",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.jpa.properties.hibernate.generate_statistics=true",
        "spring.flyway.enabled=true",
        "image.storage.local.directory=./build/test-images",
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
    private lateinit var imageRepository: ExpoImageRepository

    @Autowired
    private lateinit var imageCleanupService: ImageCleanupService

    @Autowired
    private lateinit var localImageStorage: LocalImageStorage

    @Autowired
    private lateinit var validationService: GetExpoValidationServiceImpl

    @Autowired
    private lateinit var deletionClient: ExpoDeletionClient

    @Autowired
    private lateinit var attendances: team.startup.expo.global.attendance.ProgramAttendanceClient

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
        programDeletionCalls.clear()
        attendanceDeletionCalls.clear()
        remoteAttendances.clear()
        failedProgramDeletion = null
        remoteApplications.clear()
        jdbcTemplate.execute("TRUNCATE TABLE tb_expo_image, tb_training_program, tb_standard_program, tb_expo RESTART IDENTITY CASCADE")
    }

    @Test
    fun `v1 검증 오류는 message 안에 기존 DTO 이름과 필드 오류를 담는다`() {
        val payload = VALID_REQUEST_JSON.replace("\"설명\"", "\"${"가".repeat(1001)}\"")
        val response = postExpo(payload, "ROLE_ADMIN")
        response.statusCode() shouldBe 400
        val error = objectMapper.readTree(response.body())
        error.get("status").asInt() shouldBe 400
        val details = objectMapper.readTree(error.get("message").asString().replace('\'', '"'))
        details.get("generateExpoRequestDto").has("description") shouldBe true
        details.size() shouldBe 1
        expoRepository.count() shouldBe 0L
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
    fun `이미지 업로드 URL은 공개 조회와 Expo 연결 후에도 유지된다`() {
        val uploaded = uploadImage(pngBytes())
        uploaded.statusCode() shouldBe 201
        val url = objectMapper.readTree(uploaded.body()).get("imageURL").asString()
        val id = url.substringAfterLast('/')
        val imageResponse =
            httpClient.send(
                HttpRequest.newBuilder(URI.create("http://localhost:$port/image/$id")).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray(),
            )
        imageResponse.statusCode() shouldBe 200
        imageResponse.headers().firstValue(HttpHeaders.CONTENT_TYPE).orElseThrow() shouldBe "image/png"
        ImageIO.read(imageResponse.body().inputStream()).width shouldBe 750

        val created = postExpo(VALID_REQUEST_JSON.replace("https://example.com/cover.png", url), "ROLE_ADMIN")
        created.statusCode() shouldBe 201
        val expoId = objectMapper.readTree(created.body()).get("expoId").asString()
        expoRepository.findById(expoId).orElseThrow().coverImage shouldBe url
        imageRepository.findById(id).orElseThrow().status shouldBe ExpoImage.ATTACHED

        val reused = postExpo(VALID_REQUEST_JSON.replace("https://example.com/cover.png", url), "ROLE_ADMIN")
        assertError(reused, 409, "이미지를 연결할 수 없습니다.")
    }

    @Test
    fun `이미지 업로드는 관리자 권한과 파일 형식을 확인한다`() {
        val png = pngBytes()
        assertError(uploadImage(png, authority = null), 401, "인증이 필요합니다.")
        assertError(uploadImage(png, authority = "ROLE_USER"), 403, "접근 권한이 없습니다.")
        assertError(uploadImage("not an image".toByteArray()), 400, "올바른 이미지가 아닙니다.")
        assertError(uploadImage(png, contentType = "image/svg+xml"), 415, "JPEG 또는 PNG 이미지만 업로드할 수 있습니다.")
        imageRepository.count() shouldBe 0L
    }

    @Test
    fun `업로드되지 않은 URL과 다른 관리자 이미지는 Expo 생성에서 거부한다`() {
        val unknown = postExpo(VALID_REQUEST_JSON.replace("https://example.com/cover.png", "https://example.com/other.png"), "ROLE_ADMIN")
        assertError(unknown, 409, "업로드된 이미지를 찾을 수 없습니다.")

        val id = UUID.randomUUID().toString()
        val url = "http://localhost:8080/image/$id"
        imageRepository.saveAndFlush(ExpoImage(id, "local", "img/$id.png", url, "image/png", "different-admin"))
        val foreign = postExpo(VALID_REQUEST_JSON.replace("https://example.com/cover.png", url), "ROLE_ADMIN")
        assertError(foreign, 409, "이미지를 연결할 수 없습니다.")
        expoRepository.count() shouldBe 0L
    }

    @Test
    fun `이미지 교체는 이전 자산을 미연결 상태로 바꾸고 새 자산을 연결한다`() {
        val firstUrl = objectMapper.readTree(uploadImage(pngBytes()).body()).get("imageURL").asString()
        val created = postExpo(VALID_REQUEST_JSON.replace("https://example.com/cover.png", firstUrl), "ROLE_ADMIN")
        val expoId = objectMapper.readTree(created.body()).get("expoId").asString()
        val expo = expoRepository.findById(expoId).orElseThrow()
        val standardId = standardProgramRepository.findByExpo(expo).single().id!!
        val trainingId = trainingProgramRepository.findByExpo(expo).single().id!!
        val secondUrl = objectMapper.readTree(uploadImage(pngBytes()).body()).get("imageURL").asString()

        val response =
            request("/expo/$expoId", "PATCH", updateRequest(standardId, trainingId).replace("https://example.com/updated.png", secondUrl))

        response.statusCode() shouldBe 204
        expoRepository.findById(expoId).orElseThrow().coverImage shouldBe secondUrl
        imageRepository.findById(firstUrl.substringAfterLast('/')).orElseThrow().status shouldBe ExpoImage.ORPHAN
        imageRepository.findById(secondUrl.substringAfterLast('/')).orElseThrow().status shouldBe ExpoImage.ATTACHED
    }

    @Test
    fun `오래된 미연결 이미지는 파일과 자산을 함께 정리한다`() {
        val id = UUID.randomUUID().toString()
        val key = "img/$id.png"
        val file = Path.of("./build/test-images/$key")
        localImageStorage.put(key, pngBytes(), "image/png")
        imageRepository.saveAndFlush(
            ExpoImage(
                id,
                "local",
                key,
                "http://localhost:8080/image/$id",
                "image/png",
                "http-test-user",
                Instant.now().minus(25, ChronoUnit.HOURS),
            ),
        )

        imageCleanupService.execute()

        imageRepository.existsById(id) shouldBe false
        Files.exists(file) shouldBe false
    }

    @Test
    fun `업로드 제한을 넘은 파일은 413 오류 형식으로 응답한다`() {
        val response = uploadImage(ByteArray(5 * 1024 * 1024 + 1))

        response.statusCode() shouldBe 413
        objectMapper.readTree(response.body()).get("status").asInt() shouldBe 413
    }

    @Test
    fun `이미지 연결 DB 실패는 Expo 생성과 자산 상태를 함께 롤백한다`() {
        jdbcTemplate.execute(
            """
            CREATE FUNCTION fail_image_attach() RETURNS trigger AS ${'$'}trigger${'$'}
            BEGIN
                IF NEW.status = 'ATTACHED' THEN
                    RAISE EXCEPTION 'forced image attachment failure';
                END IF;
                RETURN NEW;
            END;
            ${'$'}trigger${'$'} LANGUAGE plpgsql
            """.trimIndent(),
        )
        jdbcTemplate.execute(
            "CREATE TRIGGER fail_image_attach BEFORE UPDATE ON tb_expo_image FOR EACH ROW EXECUTE FUNCTION fail_image_attach()",
        )
        try {
            val response = postExpo(VALID_REQUEST_JSON, "ROLE_ADMIN")

            assertError(response, 500, "서버 오류가 발생했습니다.")
            expoRepository.count() shouldBe 0L
            imageRepository.findAll().single().status shouldBe ExpoImage.PENDING
        } finally {
            jdbcTemplate.execute("DROP TRIGGER IF EXISTS fail_image_attach ON tb_expo_image")
            jdbcTemplate.execute("DROP FUNCTION IF EXISTS fail_image_attach()")
        }
    }

    @Test
    fun `실제 HTTP 요청은 검증 인증 권한 상태를 구분한다`() {
        val badRequest = postExpo(VALID_REQUEST_JSON.replace("일반 프로그램", "가".repeat(51)), "ROLE_ADMIN")
        val unauthorized = postExpo(VALID_REQUEST_JSON)
        val forbidden = postExpo(VALID_REQUEST_JSON, authority = "ROLE_USER")

        badRequest.statusCode() shouldBe 400
        val validationError = objectMapper.readTree(badRequest.body())
        validationError.get("status").asInt() shouldBe 400
        val fields = objectMapper.readTree(validationError.get("message").asString().replace('\'', '"'))
        fields.get("generateExpoRequestDto").has("addStandardProRequestDto[0].title") shouldBe true
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
    fun `삭제는 세 소유 서비스를 순서대로 정리한 뒤 로컬 프로그램까지 삭제하고 빈 204를 반환한다`() {
        val expoId = createExpo()
        val expo = expoRepository.findById(expoId).orElseThrow()
        val standardId = standardProgramRepository.findByExpo(expo).single().id!!
        val trainingId = trainingProgramRepository.findByExpo(expo).single().id!!
        val calls = mutableListOf<String>()

        withDeletionServer { exchange ->
            exchange.requestHeaders.getFirst("X-Internal-Token") shouldBe "test-delete-token"
            calls += "${exchange.requestMethod} ${exchange.requestURI.path}"
            if (exchange.requestURI.path.endsWith("/purge")) {
                objectMapper.readTree(exchange.requestBody.readAllBytes()) shouldBe
                    objectMapper.readTree(
                        """{"standardProgramIds":[$standardId],"trainingProgramIds":[$trainingId]}""",
                    )
            }
            204
        }.use {
            val response = request("/expo/$expoId", "DELETE")
            response.statusCode() shouldBe 204
            response.body() shouldBe ""
            calls shouldBe
                listOf(
                    "POST /application/internal/expos/$expoId/purge",
                    "DELETE /form/internal/expos/$expoId",
                    "DELETE /user/internal/expos/$expoId",
                    "DELETE /attendance/internal/program-attendances/standard/$standardId",
                    "DELETE /attendance/internal/program-attendances/training/$trainingId",
                    "DELETE /attendance/internal/expos/$expoId",
                )
            expoRepository.existsById(expoId) shouldBe false
            imageRepository.findAll().single().apply {
                status shouldBe ExpoImage.ORPHAN
                this.expoId shouldBe null
            }
            standardProgramRepository.count() shouldBe 0L
            trainingProgramRepository.count() shouldBe 0L
            assertError(request("/expo/$expoId", "DELETE"), 404, "박람회를 찾지 못 했습니다.")
        }
    }

    @Test
    fun `박람회 출석과 QR 실패는 삭제표식과 목록을 남겨 같은 ID로 재시도한다`() {
        val expoId = createExpo()
        val expected =
            listOf(
                "/attendance/internal/program-attendances/standard/1",
                "/attendance/internal/program-attendances/training/1",
                "/attendance/internal/expos/$expoId",
            )
        var failure = expected[1]
        val calls = mutableListOf<String>()
        withDeletionServer { exchange ->
            val path = exchange.requestURI.path
            calls.add(path)
            if (path == failure) 500 else 204
        }.use {
            request("/expo/$expoId", "DELETE").statusCode() shouldBe 502
            standardProgramRepository.existsById(1) shouldBe true
            trainingProgramRepository.existsById(1) shouldBe true
            (expoRepository.findById(expoId).orElseThrow().deletingAt != null) shouldBe true
            request("/training/1", "DELETE").statusCode() shouldBe 409
            request("/standard/1", "DELETE").statusCode() shouldBe 409
            request("/standard/1", "PATCH", STANDARD_PROGRAM_JSON.replaceFirst("{", """{"id":1,""")).statusCode() shouldBe 409
            request("/training/1", "PATCH", TRAINING_PROGRAM_JSON.replaceFirst("{", """{"id":1,""")).statusCode() shouldBe 409
            request("/expo/$expoId", "PATCH", EMPTY_UPDATE_REQUEST_JSON).statusCode() shouldBe 409
            failure = expected[2]
            request("/expo/$expoId", "DELETE").statusCode() shouldBe 502
            standardProgramRepository.existsById(1) shouldBe true
            trainingProgramRepository.existsById(1) shouldBe true
            failure = ""
            request("/expo/$expoId", "DELETE").statusCode() shouldBe 204
            calls.filter { it.startsWith("/attendance") } shouldBe expected.take(2) + expected + expected
        }
    }

    @Test
    fun `박람회 삭제는 모든 프로그램 출석과 해당 박람회 QR만 요청한다`() {
        val expoId = createExpo()
        val otherExpoId = createExpo()
        request("/standard/$expoId", "POST", STANDARD_PROGRAM_JSON).statusCode() shouldBe 201
        request("/training/$expoId", "POST", TRAINING_PROGRAM_JSON).statusCode() shouldBe 201
        val calls = mutableListOf<String>()
        withDeletionServer { exchange ->
            if (exchange.requestURI.path.startsWith("/attendance")) {
                exchange.requestMethod shouldBe "DELETE"
                exchange.requestHeaders.getFirst("X-Internal-Token") shouldBe "test-delete-token"
                calls.add(exchange.requestURI.path)
            }
            204
        }.use {
            request("/expo/$expoId", "DELETE").statusCode() shouldBe 204
        }
        calls shouldBe
            listOf(
                "/attendance/internal/program-attendances/standard/1",
                "/attendance/internal/program-attendances/standard/3",
                "/attendance/internal/program-attendances/training/1",
                "/attendance/internal/program-attendances/training/3",
                "/attendance/internal/expos/$expoId",
            )
        expoRepository.existsById(otherExpoId) shouldBe true
        standardProgramRepository.existsById(2) shouldBe true
        trainingProgramRepository.existsById(2) shouldBe true
    }

    @Test
    fun `박람회 출석 설정 누락과 네트워크 장애는 최종 로컬 삭제를 막는다`() {
        val expoId = createExpo()
        withDeletionServer { 204 }.use {
            ReflectionTestUtils.setField(attendances, "internalToken", "")
            request("/expo/$expoId", "DELETE").statusCode() shouldBe 503
            expoRepository.findById(expoId).orElseThrow().deletingAt shouldBe null
            ReflectionTestUtils.setField(attendances, "internalToken", "test-delete-token")
            val closed = java.net.ServerSocket(0)
            val closedPort = closed.localPort
            closed.close()
            ReflectionTestUtils.setField(attendances, "serviceUrl", "http://127.0.0.1:$closedPort")
            request("/expo/$expoId", "DELETE").statusCode() shouldBe 503
            (expoRepository.findById(expoId).orElseThrow().deletingAt != null) shouldBe true
            standardProgramRepository.count() shouldBe 1L
            trainingProgramRepository.count() shouldBe 1L
        }
    }

    @Test
    fun `소유 서비스 실패는 삭제를 중단하고 재시도 시 같은 프로그램 ID로 이어간다`() {
        val expoId = createExpo()
        jdbcTemplate.update(
            """INSERT INTO tb_preregister_session (expo_id,title,started_at,ended_at,place,capacity,waiting_capacity,closed)
               VALUES (?,'회차','2026-09-24T00:00:00Z','2026-09-24T01:00:00Z','광주',100,10,false)""",
            expoId,
        )
        val formAttempts = AtomicInteger()
        jdbcTemplate.update(
            """INSERT INTO tb_preregister_session_change (session_id,change_id,next_revision,operation,definition_changed)
               SELECT id,'0190abcd-0000-7000-8000-000000000003',2,'DELETE',true FROM tb_preregister_session WHERE expo_id=?""",
            expoId,
        )
        val applicationBodies = mutableListOf<String>()
        withDeletionServer { exchange ->
            when {
                exchange.requestURI.path.endsWith("/purge") -> {
                    applicationBodies += String(exchange.requestBody.readAllBytes())
                    204
                }

                formAttempts.getAndIncrement() == 0 -> {
                    503
                }

                else -> {
                    204
                }
            }
        }.use {
            assertError(request("/expo/$expoId", "DELETE"), 502, "Form 서비스 삭제 응답을 확인할 수 없습니다.")
            expoRepository
                .findById(expoId)
                .orElseThrow()
                .deletingAt
                ?.let { true } shouldBe true
            standardProgramRepository.count() shouldBe 1L
            trainingProgramRepository.count() shouldBe 1L
            assertError(request("/expo/$expoId", "PATCH", EMPTY_UPDATE_REQUEST_JSON), 409, "삭제 중인 박람회입니다.")
            jdbcTemplate.queryForObject("SELECT COUNT(*) FROM tb_preregister_session WHERE expo_id = ?", Long::class.java, expoId) shouldBe
                1L
            jdbcTemplate.queryForObject("SELECT COUNT(*) FROM tb_preregister_session_change", Long::class.java) shouldBe 1L

            request("/expo/$expoId", "DELETE").statusCode() shouldBe 204
            applicationBodies.size shouldBe 2
            applicationBodies[0] shouldBe applicationBodies[1]
            expoRepository.existsById(expoId) shouldBe false
            jdbcTemplate.queryForObject("SELECT COUNT(*) FROM tb_preregister_session WHERE expo_id = ?", Long::class.java, expoId) shouldBe
                0L
            jdbcTemplate.queryForObject("SELECT COUNT(*) FROM tb_preregister_session_change", Long::class.java) shouldBe 0L
        }
    }

    @Test
    fun `삭제는 권한과 내부 연동 설정을 확인한다`() {
        val expoId = createExpo()
        assertError(request("/expo/not-found", "DELETE"), 404, "박람회를 찾지 못 했습니다.")
        assertError(request("/expo/$expoId", "DELETE", authority = null), 401, "인증이 필요합니다.")
        assertError(request("/expo/$expoId", "DELETE", authority = "ROLE_USER"), 403, "접근 권한이 없습니다.")
        assertError(request("/expo/$expoId", "DELETE"), 503, "박람회 삭제 내부 연동이 설정되지 않았습니다.")
        expoRepository.findById(expoId).orElseThrow().deletingAt shouldBe null
    }

    private fun withDeletionServer(respond: (com.sun.net.httpserver.HttpExchange) -> Int): AutoCloseable {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val status = respond(exchange)
            exchange.sendResponseHeaders(status, -1)
            exchange.close()
        }
        server.start()
        val url = "http://127.0.0.1:${server.address.port}"
        ReflectionTestUtils.setField(deletionClient, "applicationUrl", "$url/application")
        ReflectionTestUtils.setField(deletionClient, "formUrl", "$url/form")
        ReflectionTestUtils.setField(deletionClient, "userUrl", "$url/user")
        ReflectionTestUtils.setField(deletionClient, "internalToken", "test-delete-token")
        ReflectionTestUtils.setField(attendances, "serviceUrl", "$url/attendance")
        ReflectionTestUtils.setField(attendances, "internalToken", "test-delete-token")
        return AutoCloseable {
            server.stop(0)
            ReflectionTestUtils.setField(attendances, "serviceUrl", "http://127.0.0.1:${applicationServer.address.port}")
            ReflectionTestUtils.setField(attendances, "internalToken", "test-program-token")
            listOf("applicationUrl", "formUrl", "userUrl", "internalToken").forEach {
                ReflectionTestUtils.setField(deletionClient, it, "")
            }
        }
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
                    """{"expoValid":[{"expoId":"$expoId","preStandardFormCreatedStatus":true,"siteStandardFormCreatedStatus":false,"traineeFormCreatedStatus":true,"standardSurveyCreatedStatus":true,"traineeSurveyCreatedStatus":false}]}""",
                )
            (maxActiveRequests.get() > 1) shouldBe true
        } finally {
            server.stop(0)
            serverExecutor.shutdown()
            ReflectionTestUtils.setField(validationService, "formServiceUrl", "")
        }
    }

    @Test
    fun `Form 연결 실패는 503이고 비정상 HTTP 응답은 502다`() {
        createExpo()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            exchange.sendResponseHeaders(500, -1)
            exchange.close()
        }
        server.start()
        val url = "http://127.0.0.1:${server.address.port}"
        try {
            ReflectionTestUtils.setField(validationService, "formServiceUrl", url)
            assertError(request("/expo/valid", "GET"), 502, "Form 서비스 응답을 확인할 수 없습니다.")
            server.stop(0)
            assertError(request("/expo/valid", "GET"), 503, "Form 서비스에 연결할 수 없습니다.")
        } finally {
            server.stop(0)
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
        assertError(detailResponse, expectedStatus = 404, expectedMessage = "박람회를 찾지 못 했습니다.")
    }

    @Test
    fun `내부 박람회 조회는 공유 토큰으로 제목과 날짜만 반환하고 없는 박람회는 404다`() {
        val expoId = createExpo()

        val response = request("/internal/expo/$expoId", "GET", authority = null, internalToken = "test-internal-token")
        val period = objectMapper.readTree(response.body())

        response.statusCode() shouldBe 200
        period.size() shouldBe 3
        period.get("title").asString() shouldBe "2026 박람회"
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
    fun `내부 연수 프로그램 일괄 조회는 중복을 합쳐 ID순으로 반환하고 시간을 yyyy-MM-dd HH mm으로 맞춘다`() {
        val expoId = createExpo()
        val expo = expoRepository.findById(expoId).orElseThrow()
        val firstId = trainingProgramRepository.findByExpo(expo).single().id!!
        val secondId =
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

        val response = internalBatch(expoId, """{"programIds":[$secondId,$firstId,$secondId]}""")
        val empty = internalBatch(expoId, """{"programIds":[]}""")

        response.statusCode() shouldBe 200
        objectMapper.readTree(response.body()) shouldBe
            objectMapper.readTree(
                """[
                    {"id":$firstId,"title":"연수 프로그램","startedAt":"2026-09-24 10:00","endedAt":"2026-09-24 11:00","category":"ESSENTIAL"},
                    {"id":$secondId,"title":"두 번째 연수","startedAt":"2026-09-24 13:00","endedAt":"2026-09-24 14:00","category":"CHOICE"}
                ]""",
            )
        empty.statusCode() shouldBe 200
        objectMapper.readTree(empty.body()).isEmpty shouldBe true
    }

    @Test
    fun `내부 연수 프로그램 일괄 조회는 다른 박람회나 없는 ID와 잘못된 요청을 명시적으로 거부한다`() {
        val expoId = createExpo()
        val otherExpoId = createExpo()
        val programId = trainingProgramRepository.findByExpo(expoRepository.findById(expoId).orElseThrow()).single().id!!
        val otherProgramId = trainingProgramRepository.findByExpo(expoRepository.findById(otherExpoId).orElseThrow()).single().id!!

        assertError(internalBatch(expoId, """{"programIds":[$programId,$otherProgramId]}"""), 404, "연수 프로그램을 찾지 못했습니다.")
        assertError(internalBatch(expoId, """{"programIds":[$programId,999999]}"""), 404, "연수 프로그램을 찾지 못했습니다.")
        assertError(internalBatch("not-found", """{"programIds":[$programId]}"""), 404, "박람회를 찾을 수 없습니다.")
        assertError(internalBatch("not-found", """{"programIds":[]}"""), 404, "박람회를 찾을 수 없습니다.")
        for (body in listOf("{}", """{"programIds":null}""", """{"programIds":[null]}""", """{"programIds":["a"]}""")) {
            assertError(internalBatch(expoId, body), 400, "잘못된 요청입니다.")
        }
        val limit = (1..100).joinToString(",", "[", "]") { programId.toString() }
        internalBatch(expoId, """{"programIds":$limit}""").statusCode() shouldBe 200
        val overLimit = (1..101).joinToString(",", "[", "]") { programId.toString() }
        assertError(internalBatch(expoId, """{"programIds":$overLimit}"""), 400, "잘못된 요청입니다.")
    }

    @Test
    fun `내부 일반 프로그램 조회는 같은 박람회 소속일 때만 ID와 제목을 반환한다`() {
        val expoId = createExpo()
        val otherExpoId = createExpo()
        val programId = standardProgramRepository.findByExpo(expoRepository.findById(expoId).orElseThrow()).single().id!!
        val otherProgramId = standardProgramRepository.findByExpo(expoRepository.findById(otherExpoId).orElseThrow()).single().id!!

        val response = internalStandard(expoId, programId.toString())

        response.statusCode() shouldBe 200
        objectMapper.readTree(response.body()) shouldBe objectMapper.readTree("""{"id":$programId,"title":"일반 프로그램"}""")
        assertError(internalStandard(expoId, otherProgramId.toString()), 404, "일반 프로그램을 찾지 못 했습니다.")
        assertError(internalStandard(expoId, "999999"), 404, "일반 프로그램을 찾지 못 했습니다.")
        assertError(internalStandard("not-found", programId.toString()), 404, "박람회를 찾을 수 없습니다.")
        assertError(internalStandard(expoId, "abc"), 400, "잘못된 요청입니다.")
    }

    @Test
    fun `내부 프로그램 조회는 토큰 누락과 불일치를 거부하고 관리자 JWT로 대체되지 않는다`() {
        val expoId = createExpo()
        val expo = expoRepository.findById(expoId).orElseThrow()
        val trainingId = trainingProgramRepository.findByExpo(expo).single().id!!
        val standardId = standardProgramRepository.findByExpo(expo).single().id!!
        val batch = "/internal/expo/$expoId/training-programs/batch" to """{"programIds":[$trainingId]}"""
        val standard = "/internal/expo/$expoId/standard-programs/$standardId"

        assertError(request(batch.first, "POST", batch.second, authority = null), 401, "인증이 필요합니다.")
        assertError(request(batch.first, "POST", batch.second, authority = null, internalToken = "wrong-token"), 401, "인증이 필요합니다.")
        assertError(request(batch.first, "POST", batch.second, authority = "ROLE_ADMIN"), 401, "인증이 필요합니다.")
        assertError(request(standard, "GET", authority = null), 401, "인증이 필요합니다.")
        assertError(request(standard, "GET", authority = null, internalToken = "wrong-token"), 401, "인증이 필요합니다.")
        assertError(request(standard, "GET", authority = "ROLE_ADMIN"), 401, "인증이 필요합니다.")
        assertError(request(batch.first, "GET", authority = null, internalToken = "test-internal-token"), 403, "접근 권한이 없습니다.")
        assertError(request(standard, "POST", "{}", authority = null, internalToken = "test-internal-token"), 403, "접근 권한이 없습니다.")
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
    fun `실제 HTTP 프로그램 조회는 공개 빈 목록과 없는 Expo를 구분한다`() {
        val emptyRequest = objectMapper.readTree(VALID_REQUEST_JSON) as ObjectNode
        emptyRequest.putArray("addStandardProRequestDto")
        emptyRequest.putArray("addTrainingProRequestDto")
        val expoId = objectMapper.readTree(postExpo(emptyRequest.toString(), "ROLE_ADMIN").body()).get("expoId").asString()

        for (path in listOf("/standard/program", "/training/program")) {
            val empty = request("$path/$expoId", "GET")
            empty.statusCode() shouldBe 200
            objectMapper.readTree(empty.body()).isEmpty shouldBe true
            assertError(request("$path/not-found", "GET", authority = null), 404, "박람회를 찾지 못 했습니다.")
            request("$path/$expoId", "GET", authority = null).statusCode() shouldBe 200
            request("$path/$expoId", "GET", authority = "ROLE_USER").statusCode() shouldBe 200
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
        programDeletionCalls.toList() shouldBe
            listOf("/internal/standard-program-applications/program/1", "/internal/training-program-applications/program/1")
        attendanceDeletionCalls.toList() shouldBe
            listOf("/internal/program-attendances/standard/1", "/internal/program-attendances/training/1")
    }

    @Test
    fun `누락 프로그램 원격 정리 실패는 로컬을 보존하고 재시도는 이미 정리된 신청도 안전하게 처리한다`() {
        val expoId = createExpo()
        val before = databaseSnapshot(expoId)
        val standardPath = "/internal/standard-program-applications/program/1"
        val trainingPath = "/internal/training-program-applications/program/1"
        remoteApplications.addAll(listOf(standardPath, trainingPath))
        failedProgramDeletion = standardPath
        request("/expo/$expoId", "PATCH", EMPTY_UPDATE_REQUEST_JSON).statusCode() shouldBe 502
        databaseSnapshot(expoId) shouldBe before
        remoteApplications.toSet() shouldBe setOf(standardPath, trainingPath)
        programDeletionCalls.toList() shouldBe listOf(standardPath)

        failedProgramDeletion = trainingPath
        request("/expo/$expoId", "PATCH", EMPTY_UPDATE_REQUEST_JSON).statusCode() shouldBe 502
        databaseSnapshot(expoId) shouldBe before
        remoteApplications.toSet() shouldBe setOf(trainingPath)

        failedProgramDeletion = null
        request("/expo/$expoId", "PATCH", EMPTY_UPDATE_REQUEST_JSON).statusCode() shouldBe 204
        remoteApplications.isEmpty() shouldBe true
        standardProgramRepository.count() shouldBe 0L
        trainingProgramRepository.count() shouldBe 0L
        programDeletionCalls.toList() shouldBe listOf(standardPath, standardPath, trainingPath, standardPath, trainingPath)
        programDeletionCalls.clear()
        request("/expo/$expoId", "PATCH", EMPTY_UPDATE_REQUEST_JSON).statusCode() shouldBe 204
        programDeletionCalls.isEmpty() shouldBe true
    }

    @Test
    fun `PATCH는 누락 ID만 정리하고 유지하는 프로그램의 신청은 건드리지 않는다`() {
        val expoId = createExpo()
        request("/standard/$expoId", "POST", STANDARD_PROGRAM_JSON).statusCode() shouldBe 201
        remoteApplications.addAll(
            listOf(
                "/internal/standard-program-applications/program/1",
                "/internal/standard-program-applications/program/2",
                "/internal/training-program-applications/program/1",
            ),
        )
        request("/expo/$expoId", "PATCH", updateRequest(1, 1)).statusCode() shouldBe 204
        programDeletionCalls.toList() shouldBe listOf("/internal/standard-program-applications/program/2")
        remoteApplications.toSet() shouldBe
            setOf("/internal/standard-program-applications/program/1", "/internal/training-program-applications/program/1")
        standardProgramRepository.existsById(1) shouldBe true
        standardProgramRepository.existsById(2) shouldBe false
        trainingProgramRepository.existsById(1) shouldBe true
    }

    @Test
    fun `원격 정리 성공 뒤 로컬 삭제 실패는 DB를 롤백하고 같은 요청 재시도로 완료한다`() {
        val expoId = createExpo()
        val before = databaseSnapshot(expoId)
        remoteApplications.addAll(
            listOf("/internal/standard-program-applications/program/1", "/internal/training-program-applications/program/1"),
        )
        try {
            jdbcTemplate.execute(
                """
                CREATE FUNCTION fail_program_delete() RETURNS trigger AS ${'$'}trigger${'$'}
                BEGIN
                    RAISE EXCEPTION 'forced deletion failure';
                END;
                ${'$'}trigger${'$'} LANGUAGE plpgsql;
                """.trimIndent(),
            )
            jdbcTemplate.execute(
                "CREATE TRIGGER fail_program_delete BEFORE DELETE ON tb_training_program FOR EACH ROW EXECUTE FUNCTION fail_program_delete()",
            )
            request("/expo/$expoId", "PATCH", EMPTY_UPDATE_REQUEST_JSON).statusCode() shouldBe 500
            databaseSnapshot(expoId) shouldBe before
            remoteApplications.isEmpty() shouldBe true
        } finally {
            jdbcTemplate.execute("DROP TRIGGER IF EXISTS fail_program_delete ON tb_training_program")
            jdbcTemplate.execute("DROP FUNCTION IF EXISTS fail_program_delete()")
        }
        request("/expo/$expoId", "PATCH", EMPTY_UPDATE_REQUEST_JSON).statusCode() shouldBe 204
        programDeletionCalls.size shouldBe 4
        standardProgramRepository.count() shouldBe 0L
        trainingProgramRepository.count() shouldBe 0L
    }

    @Test
    fun `PATCH 출석 정리 일부 성공은 로컬을 보존하고 재시도하며 다른 ID 영역을 유지한다`() {
        val expoId = createExpo()
        request("/standard/$expoId", "POST", STANDARD_PROGRAM_JSON).statusCode() shouldBe 201
        val before = databaseSnapshot(expoId)
        val standard = "/internal/program-attendances/standard/2"
        val training = "/internal/program-attendances/training/1"
        remoteAttendances.addAll(listOf(standard, training, "/internal/program-attendances/standard/1"))
        val payload = objectMapper.readTree(updateRequest(1, 1)) as ObjectNode
        payload.withArray("updateTrainingProRequestDto").removeAll()
        failedProgramDeletion = training
        request("/expo/$expoId", "PATCH", payload.toString()).statusCode() shouldBe 502
        databaseSnapshot(expoId) shouldBe before
        remoteAttendances.toSet() shouldBe setOf(training, "/internal/program-attendances/standard/1")
        failedProgramDeletion = null
        request("/expo/$expoId", "PATCH", payload.toString()).statusCode() shouldBe 204
        remoteAttendances.toSet() shouldBe setOf("/internal/program-attendances/standard/1")
        attendanceDeletionCalls.toList() shouldBe listOf(standard, training, standard, training)
    }

    @Test
    fun `PATCH는 잘못된 이미지와 DTO를 원격 정리 전에 거부한다`() {
        val expoId = createExpo()
        val before = databaseSnapshot(expoId)
        val invalidImage = EMPTY_UPDATE_REQUEST_JSON.replace("https://example.com/cover.png", "https://invalid.example/image.png")
        request("/expo/$expoId", "PATCH", invalidImage).statusCode() shouldBe 409
        val invalidCoordinate = objectMapper.readTree(EMPTY_UPDATE_REQUEST_JSON) as ObjectNode
        invalidCoordinate.put("x", "가".repeat(16))
        request("/expo/$expoId", "PATCH", invalidCoordinate.toString()).statusCode() shouldBe 400
        databaseSnapshot(expoId) shouldBe before
        programDeletionCalls.isEmpty() shouldBe true
        attendanceDeletionCalls.isEmpty() shouldBe true
    }

    @Test
    fun `PATCH 저장 실패는 누락 프로그램 원격 신청을 삭제하지 않는다`() {
        val expoId = createExpo()
        val before = databaseSnapshot(expoId)
        val payload = objectMapper.readTree(updateRequest(1, 1).replace("수정 연수", "롤백 연수")) as ObjectNode
        payload.withArray("updateStandardProRequestDto").removeAll()
        withTrainingWriteFailure {
            request("/expo/$expoId", "PATCH", payload.toString()).statusCode() shouldBe 500
            databaseSnapshot(expoId) shouldBe before
            programDeletionCalls.isEmpty() shouldBe true
        }
    }

    @Test
    fun `박람회 중첩 프로그램은 유니코드 50문자를 생성 수정하고 51문자를 거절한다`() {
        val title = "😀".repeat(50)
        val payload =
            VALID_REQUEST_JSON
                .replace("일반 프로그램", title)
                .replace("연수 프로그램", title)
                .replace("127.123", "😀".repeat(15))
                .replace("37.456", "😀".repeat(15))
        val created = postExpo(payload, authority = "ROLE_ADMIN")
        created.statusCode() shouldBe 201
        val expoId = objectMapper.readTree(created.body()).get("expoId").asString()
        val update =
            updateRequest(1, 1)
                .replace("수정 일반", title)
                .replace("수정 연수", title)
                .replace("신규 일반", title)
                .replace("신규 연수", title)
                .replace("127.456", "😀".repeat(15))
                .replace("37.789", "😀".repeat(15))
        request("/expo/$expoId", "PATCH", update).statusCode() shouldBe 204
        for (path in listOf("standard", "training")) {
            objectMapper.readTree(request("/$path/program/$expoId", "GET", authority = null).body()).forEach {
                it.get("title").asString() shouldBe title
            }
        }
        request("/expo/$expoId", "PATCH", update.replace(title, "😀".repeat(51))).statusCode() shouldBe 400
        postExpo(payload.replace(title, "😀".repeat(51)), authority = "ROLE_ADMIN").statusCode() shouldBe 400
        programDeletionCalls.isEmpty() shouldBe true
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
            programDeletionCalls.isEmpty() shouldBe true
        }
    }

    @Test
    fun `실제 HTTP 수정은 없는 박람회를 404로 응답한다`() {
        val response = request("/expo/not-found", "PATCH", EMPTY_UPDATE_REQUEST_JSON)

        assertError(response, expectedStatus = 404, expectedMessage = "박람회를 찾지 못 했습니다.")
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
    fun `프로그램 변경은 락 대기 중 커밋된 박람회 삭제표식을 확인한다`() {
        val expoId = createExpo()
        for ((kind, method) in listOf("training" to "DELETE", "training" to "PATCH", "standard" to "DELETE", "standard" to "PATCH")) {
            val body =
                if (method ==
                    "DELETE"
                ) {
                    null
                } else {
                    (
                        if (kind ==
                            "training"
                        ) {
                            TRAINING_PROGRAM_JSON
                        } else {
                            STANDARD_PROGRAM_JSON
                        }
                    ).replaceFirst("{", """{"id":1,""")
                }
            postgres.createConnection("").use { connection ->
                connection.autoCommit = false
                try {
                    connection.prepareStatement("SELECT id FROM tb_expo WHERE id=? FOR UPDATE").use { statement ->
                        statement.setString(1, expoId)
                        statement.executeQuery().close()
                    }
                    val change = CompletableFuture.supplyAsync { request("/$kind/1", method, body) }
                    awaitPatchRowLock() shouldBe true
                    connection.prepareStatement("UPDATE tb_expo SET deleting_at=CURRENT_TIMESTAMP WHERE id=?").use { statement ->
                        statement.setString(1, expoId)
                        statement.executeUpdate() shouldBe 1
                    }
                    connection.commit()
                    change.get(10, TimeUnit.SECONDS).statusCode() shouldBe 409
                    programDeletionCalls.isEmpty() shouldBe true
                    attendanceDeletionCalls.isEmpty() shouldBe true
                } finally {
                    connection.rollback()
                    jdbcTemplate.update("UPDATE tb_expo SET deleting_at=NULL WHERE id=?", expoId)
                }
            }
        }
    }

    @Test
    fun `프로그램 수정이 삭제 락을 기다리면 삭제된 프로그램을 다시 만들지 않는다`() {
        val expoId = createExpo()
        for ((kind, body) in listOf("standard" to STANDARD_PROGRAM_JSON, "training" to TRAINING_PROGRAM_JSON)) {
            postgres.createConnection("").use { connection ->
                connection.autoCommit = false
                try {
                    connection.prepareStatement("SELECT id FROM tb_expo WHERE id=? FOR UPDATE").use { statement ->
                        statement.setString(1, expoId)
                        statement.executeQuery().use { it.next() shouldBe true }
                    }
                    val update = CompletableFuture.supplyAsync { request("/$kind/1", "PATCH", body.replaceFirst("{", """{"id":1,""")) }
                    awaitPatchRowLock() shouldBe true
                    connection.createStatement().use { it.executeUpdate("DELETE FROM tb_${kind}_program WHERE id=1") shouldBe 1 }
                    connection.commit()
                    update.get(10, TimeUnit.SECONDS).statusCode() shouldBe 404
                    jdbcTemplate.queryForObject("SELECT count(*) FROM tb_${kind}_program WHERE id=1", Long::class.java) shouldBe 0L
                } finally {
                    connection.rollback()
                }
            }
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

    private fun internalBatch(
        expoId: String,
        body: String,
    ): HttpResponse<String> =
        request("/internal/expo/$expoId/training-programs/batch", "POST", body, authority = null, internalToken = "test-internal-token")

    private fun internalStandard(
        expoId: String,
        programId: String,
    ): HttpResponse<String> =
        request("/internal/expo/$expoId/standard-programs/$programId", "GET", authority = null, internalToken = "test-internal-token")

    private fun postExpo(
        body: String,
        authority: String? = null,
    ): HttpResponse<String> = request("/expo", "POST", body, authority)

    private fun uploadImage(
        bytes: ByteArray,
        contentType: String = "image/png",
        authority: String? = "ROLE_ADMIN",
    ): HttpResponse<String> {
        val boundary = "image-test-boundary"
        val header =
            "--$boundary\r\nContent-Disposition: form-data; name=\"image\"; filename=\"cover.png\"\r\n" +
                "Content-Type: $contentType\r\n\r\n"
        val payload = header.toByteArray() + bytes + "\r\n--$boundary--\r\n".toByteArray()
        val request =
            HttpRequest
                .newBuilder(URI.create("http://localhost:$port/image"))
                .header(HttpHeaders.CONTENT_TYPE, "multipart/form-data; boundary=$boundary")
                .apply { authority?.let { header(HttpHeaders.AUTHORIZATION, "Bearer ${TestJwt.token(it)}") } }
                .POST(HttpRequest.BodyPublishers.ofByteArray(payload))
                .build()
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString())
    }

    private fun pngBytes(): ByteArray =
        ByteArrayOutputStream().use { output ->
            ImageIO.write(BufferedImage(750, 360, BufferedImage.TYPE_INT_RGB), "png", output)
            output.toByteArray()
        }

    private fun request(
        path: String,
        method: String,
        body: String? = null,
        authority: String? = "ROLE_ADMIN",
        internalToken: String? = null,
    ): HttpResponse<String> {
        val requestBody =
            if (authority == "ROLE_ADMIN" && path.startsWith("/expo") && body != null) {
                Regex("https://example\\.com/(cover|updated)\\.png").replace(body) { imageFixtureUrl() }
            } else {
                body
            }
        val request =
            HttpRequest
                .newBuilder(URI.create("http://localhost:$port$path"))
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .apply {
                    authority?.let { header(HttpHeaders.AUTHORIZATION, "Bearer ${TestJwt.token(it)}") }
                    internalToken?.let { header("X-Internal-Token", it) }
                }.method(
                    method,
                    requestBody?.let(HttpRequest.BodyPublishers::ofString) ?: HttpRequest.BodyPublishers.noBody(),
                ).build()

        return httpClient.send(request, HttpResponse.BodyHandlers.ofString())
    }

    private fun imageFixtureUrl(): String {
        val id = UUID.randomUUID().toString()
        val url = "http://localhost:8080/image/$id"
        imageRepository.saveAndFlush(
            ExpoImage(
                id = id,
                storageProvider = "local",
                objectKey = "img/$id.png",
                publicUrl = url,
                contentType = "image/png",
                uploadedBy = "1",
            ),
        )
        return url
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
                    "SELECT COUNT(*) FROM pg_stat_activity WHERE wait_event_type = 'Lock' AND query ILIKE '%tb_expo%'",
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
        @AfterAll
        fun stopApplicationServer() {
            applicationServer.stop(0)
        }

        private val programDeletionCalls = ConcurrentLinkedQueue<String>()
        private val attendanceDeletionCalls = ConcurrentLinkedQueue<String>()
        private val remoteAttendances = ConcurrentLinkedQueue<String>()
        private val remoteApplications = ConcurrentLinkedQueue<String>()
        private var failedProgramDeletion: String? = null
        private val applicationServer =
            HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
                createContext("/") { exchange ->
                    val path = exchange.requestURI.path
                    if (path.startsWith(
                            "/internal/program-attendances",
                        )
                    ) {
                        attendanceDeletionCalls.add(path)
                    } else {
                        programDeletionCalls.add(path)
                    }
                    val valid =
                        exchange.requestMethod == "DELETE" &&
                            exchange.requestHeaders.getFirst("X-Internal-Token") == "test-program-token"
                    val status = if (!valid || path == failedProgramDeletion) 500 else 204
                    if (status == 204) remoteApplications.remove(path)
                    if (status == 204) remoteAttendances.remove(path)
                    exchange.sendResponseHeaders(status, -1)
                    exchange.close()
                }
                start()
            }

        @JvmStatic
        @DynamicPropertySource
        fun applicationProperties(registry: DynamicPropertyRegistry) {
            registry.add("expo.attention.service-url") { "http://127.0.0.1:${applicationServer.address.port}" }
            registry.add("expo.attention.internal-token") { "test-program-token" }
            for (domain in listOf("standard", "training")) {
                registry.add("expo.$domain.application-service-url") { "http://127.0.0.1:${applicationServer.address.port}" }
                registry.add("expo.$domain.internal-token") { "test-program-token" }
            }
        }

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
