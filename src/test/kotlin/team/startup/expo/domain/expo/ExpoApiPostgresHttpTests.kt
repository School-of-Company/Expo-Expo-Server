package team.startup.expo.domain.expo

import io.kotest.matchers.collections.shouldBeIn
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
import team.startup.expo.domain.image.entity.ExpoImage
import team.startup.expo.domain.image.repository.ExpoImageRepository
import team.startup.expo.domain.image.service.ImageCleanupService
import team.startup.expo.domain.image.storage.impl.LocalImageStorage
import team.startup.expo.domain.standard.repository.StandardProgramRepository
import team.startup.expo.domain.training.repository.TrainingProgramRepository
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.ObjectNode
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
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
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO

@SpringBootTest(
    webEnvironment = RANDOM_PORT,
    properties = [
        "eureka.client.enabled=false",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.flyway.enabled=true",
        "image.storage.local.directory=./build/test-images",
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
    private lateinit var imageRepository: ExpoImageRepository

    @Autowired
    private lateinit var imageCleanupService: ImageCleanupService

    @Autowired
    private lateinit var localImageStorage: LocalImageStorage

    @Autowired
    private lateinit var standardProgramRepository: StandardProgramRepository

    @Autowired
    private lateinit var trainingProgramRepository: TrainingProgramRepository

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    private val httpClient = HttpClient.newHttpClient()

    @BeforeEach
    fun clearTables() {
        jdbcTemplate.execute("TRUNCATE TABLE tb_expo_image, tb_training_program, tb_standard_program, tb_expo RESTART IDENTITY")
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
            program.startedAt shouldBe "2026-09-24 09:00"
            program.endedAt shouldBe "2026-09-24 10:00"
            program.expo?.id shouldBe expoId
        }
        trainingProgramRepository.findByExpo(expo).single().let { program ->
            program.title shouldBe "연수 프로그램"
            program.startedAt shouldBe "2026-09-24 10:00"
            program.endedAt shouldBe "2026-09-24 11:00"
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

        assertError(badRequest, expectedStatus = 400, expectedMessage = "잘못된 요청입니다.")
        assertError(unauthorized, expectedStatus = 401, expectedMessage = "인증이 필요합니다.")
        assertError(forbidden, expectedStatus = 403, expectedMessage = "접근 권한이 없습니다.")
        expoRepository.count() shouldBe 0L
    }

    @Test
    fun `Spring 기본 예외는 원래 상태 코드를 유지하고 health는 인증 없이 열린다`() {
        val unsupportedMediaType =
            httpClient.send(
                HttpRequest
                    .newBuilder(URI.create("http://localhost:$port/expo"))
                    .header(HttpHeaders.CONTENT_TYPE, MediaType.TEXT_PLAIN_VALUE)
                    .header(TEST_AUTHORITY_HEADER, "ROLE_ADMIN")
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
                .apply { authority?.let { header(TEST_AUTHORITY_HEADER, it) } }
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
                    authority?.let { header(TEST_AUTHORITY_HEADER, it) }
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
                uploadedBy = "http-test-user",
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
