package team.startup.expo.domain.training

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.persistence.autoconfigure.EntityScan
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import team.startup.expo.domain.expo.HttpTestAuthenticationConfiguration
import team.startup.expo.domain.training.presentation.dto.request.ApplyTrainingProgramRequest
import team.startup.expo.domain.training.service.ApplyTrainingProgramService
import tools.jackson.databind.ObjectMapper
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.concurrent.ConcurrentLinkedQueue

@SpringBootTest(
    webEnvironment = RANDOM_PORT,
    properties = ["eureka.client.enabled=false", "spring.jpa.hibernate.ddl-auto=validate"],
)
@EntityScan("team.startup.expo.domain")
@Import(HttpTestAuthenticationConfiguration::class)
@Testcontainers
class TrainingApplicationHttpContractTests {
    @LocalServerPort
    private var port: Int = 0

    @Autowired
    private lateinit var jdbc: JdbcTemplate

    @Autowired
    private lateinit var mapper: ObjectMapper

    @Autowired
    private lateinit var applications: ApplyTrainingProgramService

    private val http = HttpClient.newHttpClient()
    private val expoId = "training-contract-expo"

    @BeforeEach
    fun setUp() {
        calls.clear()
        userResolveStatus = 200
        userNamesStatus = 200
        applicationCreateStatus = 201
        applicationListStatus = 200
        applicationDeleteStatus = 204
        userResolveBody = """{"traineeId":42}"""
        userNamesBody = """[{"traineeId":42,"name":"홍길동"}]"""
        applicationListBody = "[]"
        jdbc.execute("TRUNCATE TABLE tb_training_program, tb_standard_program, tb_expo RESTART IDENTITY")
        jdbc.update(
            """INSERT INTO tb_expo (id,title,description,started_day,finished_day,location,x,y,application_person,yesterday_application_person)
               VALUES (?,?,?,?,?,?,?,?,?,?)""",
            expoId,
            "박람회",
            "설명",
            "2026-09-24",
            "2026-09-25",
            "서울",
            "127",
            "37",
            0,
            0,
        )
        jdbc.update(
            """INSERT INTO tb_training_program (title,started_at,ended_at,category,expo_id) VALUES (?,?,?,?,?)""",
            "연수",
            "2026-09-24T09:00",
            "2026-09-24T10:00",
            "CHOICE",
            expoId,
        )
    }

    @Test
    fun `신청자 목록은 신청 ID 상태와 nullable 출입 시각을 기존 응답으로 변환한다`() {
        applicationListBody =
            """[{"applicationId":7,"traineeId":42,"status":false,"entryTime":null,"leaveTime":null},
            {"applicationId":8,"traineeId":43,"status":true,"entryTime":"09:00","leaveTime":"10:00"}]"""
        userNamesBody = """[{"traineeId":42,"name":"홍길동"},{"traineeId":43,"name":"김영희"}]"""

        val response = request("GET", "/training/1")
        response.statusCode() shouldBe 200
        val trainees = mapper.readTree(response.body())
        trainees.size() shouldBe 2
        trainees[0].toString() shouldBe
            """{"id":7,"name":"홍길동","programName":"연수","status":false,"entryTime":null,"leaveTime":null}"""
        trainees[1].get("entryTime").asString() shouldBe "09:00"
        trainees[1].get("leaveTime").asString() shouldBe "10:00"
        val names = calls.single { it.path == "/internal/trainees/names" }
        names.token shouldBe "test-training-internal-token"
        mapper.readTree(names.body).get("traineeIds").size() shouldBe 2
    }

    @Test
    fun `빈 신청 목록은 빈 배열이고 없는 프로그램은 외부 호출 전에 404다`() {
        request("GET", "/training/999").statusCode() shouldBe 404
        calls.isEmpty() shouldBe true
        val response = request("GET", "/training/1")
        response.statusCode() shouldBe 200
        response.body() shouldBe "[]"
        calls.none { it.path == "/internal/trainees/names" } shouldBe true
    }

    @Test
    fun `신청 목록과 이름 조회가 일치하지 않으면 성공 응답을 만들지 않는다`() {
        applicationListBody = """[{"applicationId":7,"traineeId":42,"status":false,"entryTime":null,"leaveTime":null}]"""
        userNamesBody = "[]"
        request("GET", "/training/1").statusCode() shouldBe 502
        userNamesBody = """[{"traineeId":42,"name":"홍길동"},{"traineeId":42,"name":"중복"}]"""
        request("GET", "/training/1").statusCode() shouldBe 502
        applicationListBody = "invalid"
        request("GET", "/training/1").statusCode() shouldBe 502
    }

    @Test
    fun `삭제는 Application 정리가 성공해야 로컬 프로그램을 삭제한다`() {
        applicationDeleteStatus = 500
        request("DELETE", "/training/1").statusCode() shouldBe 502
        jdbc.queryForObject("SELECT count(*) FROM tb_training_program", Long::class.java) shouldBe 1L
        applicationDeleteStatus = 204
        val deleted = request("DELETE", "/training/1")
        deleted.statusCode() shouldBe 204
        deleted.body() shouldBe ""
        jdbc.queryForObject("SELECT count(*) FROM tb_training_program", Long::class.java) shouldBe 0L
        request("DELETE", "/training/1").statusCode() shouldBe 404
        calls.count { it.path == "/internal/training-program-applications/program/1" && it.method == "DELETE" } shouldBe 2
    }

    @Test
    fun `단건 신청의 내부 흐름은 연수자와 프로그램 카테고리를 전달한다`() {
        applications.execute(1, ApplyTrainingProgramRequest("training-1"))
        val lookup = calls.single { it.path == "/internal/trainees/resolve" }
        val lookupBody = mapper.readTree(lookup.body)
        lookupBody.get("expoId").asString() shouldBe expoId
        lookupBody.get("trainingId").asString() shouldBe "training-1"
        lookup.token shouldBe "test-training-internal-token"
        val save = calls.single { it.path == "/internal/training-program-applications" }
        val saveBody = mapper.readTree(save.body)
        saveBody.get("trainee").get("id").asLong() shouldBe 42L
        saveBody.get("programs")[0].get("category").asString() shouldBe "CHOICE"
        applicationCreateStatus = 409
        runCatching { applications.execute(1, ApplyTrainingProgramRequest("training-1")) }.isFailure shouldBe true
    }

    @Test
    fun `본인 확인 전 공개 단건 신청은 차단하고 관리자 조회 삭제 권한을 검사한다`() {
        request("POST", "/training/application/1", """{"trainingId":"training-1"}""", "ROLE_ADMIN").statusCode() shouldBe 403
        calls.isEmpty() shouldBe true
        request("GET", "/training/1", authority = null).statusCode() shouldBe 401
        request("DELETE", "/training/1", authority = "ROLE_USER").statusCode() shouldBe 403
        calls.isEmpty() shouldBe true
    }

    private fun request(
        method: String,
        path: String,
        body: String? = null,
        authority: String? = "ROLE_ADMIN",
    ): HttpResponse<String> {
        val builder =
            HttpRequest
                .newBuilder(URI.create("http://localhost:$port$path"))
                .header("Content-Type", "application/json")
        authority?.let { builder.header("X-Test-Authority", it) }
        val payload = body?.let(HttpRequest.BodyPublishers::ofString) ?: HttpRequest.BodyPublishers.noBody()
        return http.send(builder.method(method, payload).build(), HttpResponse.BodyHandlers.ofString())
    }

    companion object {
        private data class DependencyCall(
            val path: String,
            val method: String,
            val body: String,
            val token: String?,
        )

        private val calls = ConcurrentLinkedQueue<DependencyCall>()
        private var userResolveStatus = 200
        private var userNamesStatus = 200
        private var applicationCreateStatus = 201
        private var applicationListStatus = 200
        private var applicationDeleteStatus = 204
        private var userResolveBody = """{"traineeId":42}"""
        private var userNamesBody = """[{"traineeId":42,"name":"홍길동"}]"""
        private var applicationListBody = "[]"

        private val upstream =
            HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
                createContext("/") { exchange -> respond(exchange) }
                start()
            }

        @DynamicPropertySource
        @JvmStatic
        fun dependencyProperties(registry: DynamicPropertyRegistry) {
            registry.add("expo.training.user-service-url") { "http://127.0.0.1:${upstream.address.port}" }
            registry.add("expo.training.application-service-url") { "http://127.0.0.1:${upstream.address.port}" }
            registry.add("expo.training.internal-token") { "test-training-internal-token" }
        }

        private fun respond(exchange: HttpExchange) {
            val path = exchange.requestURI.path
            calls.add(
                DependencyCall(
                    path = path,
                    method = exchange.requestMethod,
                    body = exchange.requestBody.readAllBytes().toString(Charsets.UTF_8),
                    token = exchange.requestHeaders.getFirst("X-Internal-Token"),
                ),
            )
            val programStatus = if (exchange.requestMethod == "DELETE") applicationDeleteStatus else applicationListStatus
            val status =
                when (path) {
                    "/internal/trainees/resolve" -> userResolveStatus
                    "/internal/trainees/names" -> userNamesStatus
                    "/internal/training-program-applications" -> applicationCreateStatus
                    "/internal/training-program-applications/program/1" -> programStatus
                    else -> 404
                }
            val responseBody =
                when (path) {
                    "/internal/trainees/resolve" -> userResolveBody
                    "/internal/trainees/names" -> userNamesBody
                    "/internal/training-program-applications/program/1" -> if (exchange.requestMethod == "GET") applicationListBody else ""
                    else -> ""
                }
            val bytes = responseBody.toByteArray(Charsets.UTF_8)
            exchange.sendResponseHeaders(status, if (status == 204 || bytes.isEmpty()) -1 else bytes.size.toLong())
            if (bytes.isNotEmpty() && status != 204) exchange.responseBody.write(bytes)
            exchange.close()
        }

        @Container
        @ServiceConnection
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:17-alpine")
    }
}
