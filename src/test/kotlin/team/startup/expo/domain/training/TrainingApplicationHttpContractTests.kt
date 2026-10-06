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
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import team.startup.expo.domain.training.presentation.dto.request.ApplyTrainingProgramRequest
import team.startup.expo.domain.training.service.ApplyTrainingProgramService
import team.startup.expo.support.TestJwt
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
        jdbc.execute("TRUNCATE TABLE tb_expo_image, tb_training_program, tb_standard_program, tb_expo RESTART IDENTITY CASCADE")
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
    fun `단건 신청은 무인증으로 201 빈 응답이며 실패는 상태를 보존한다`() {
        val body = """{"trainingId":"training-1"}"""
        val created = request("POST", "/training/application/1", body, authority = null)
        created.statusCode() shouldBe 201
        created.body() shouldBe ""
        calls.map { it.path } shouldBe listOf("/internal/trainees/resolve", "/internal/training-program-applications")
        calls.clear()
        request("POST", "/training/application/999", body, authority = null).statusCode() shouldBe 404
        calls.isEmpty() shouldBe true
        userResolveStatus = 404
        request("POST", "/training/application/1", body, authority = null).statusCode() shouldBe 404
        calls.none { it.path == "/internal/training-program-applications" } shouldBe true
        userResolveStatus = 200
        applicationCreateStatus = 409
        request("POST", "/training/application/1", body, authority = null).statusCode() shouldBe 409
        applicationCreateStatus = 500
        request("POST", "/training/application/1", body, authority = null).statusCode() shouldBe 502
        userResolveStatus = 500
        request("POST", "/training/application/1", body, authority = null).statusCode() shouldBe 502
    }

    @Test
    fun `다건 신청은 무인증으로 전체 프로그램을 한 번에 저장한다`() {
        addProgram("2026-09-25T09:00", expoId)
        val created =
            request("POST", "/training/application/list", """{"trainingId":"training-1","trainingProIds":[2,1]}""", authority = null)
        created.statusCode() shouldBe 201
        created.body() shouldBe ""
        val lookup = calls.single { it.path == "/internal/trainees/resolve" }
        mapper.readTree(lookup.body).get("expoId").asString() shouldBe expoId
        val save = calls.single { it.path == "/internal/training-program-applications" }
        save.token shouldBe "test-training-internal-token"
        val programs = mapper.readTree(save.body).get("programs")
        programs.size() shouldBe 2
        (0 until programs.size()).map { programs[it].get("id").asLong() }.toSet() shouldBe setOf(1L, 2L)
        (0 until programs.size()).forEach { programs[it].get("expoId").asString() shouldBe expoId }
    }

    @Test
    fun `다건 신청은 잘못된 프로그램이 하나라도 있으면 외부 저장을 호출하지 않는다`() {
        val path = "/training/application/list"
        request("POST", path, """{"trainingId":"training-1","trainingProIds":[1,999]}""", authority = null).statusCode() shouldBe 404
        request("POST", path, """{"trainingId":"training-1","trainingProIds":[1,1]}""", authority = null).statusCode() shouldBe 400
        request("POST", path, """{"trainingId":"training-1","trainingProIds":[]}""", authority = null).statusCode() shouldBe 400
        request("POST", path, """{"trainingId":"training-1","trainingProIds":[1,null]}""", authority = null).statusCode() shouldBe 400
        calls.isEmpty() shouldBe true
        jdbc.update(
            """INSERT INTO tb_expo (id,title,description,started_day,finished_day,location,x,y,application_person,yesterday_application_person)
               VALUES (?,?,?,?,?,?,?,?,?,?)""",
            "other-expo",
            "다른 박람회",
            "설명",
            "2026-09-24",
            "2026-09-25",
            "서울",
            "127",
            "37",
            0,
            0,
        )
        addProgram("2026-09-25T09:00", "other-expo")
        request("POST", path, """{"trainingId":"training-1","trainingProIds":[1,2]}""", authority = null).statusCode() shouldBe 404
        calls.isEmpty() shouldBe true
    }

    @Test
    fun `삭제 중인 행사와 빈 연수 번호는 공개 신청에서 외부 호출 전에 거절한다`() {
        request("POST", "/training/application/1", """{"trainingId":" "}""", authority = null).statusCode() shouldBe 400
        calls.isEmpty() shouldBe true
        jdbc.update("UPDATE tb_expo SET deleting_at = CURRENT_TIMESTAMP WHERE id = ?", expoId)
        request("POST", "/training/application/1", """{"trainingId":"training-1"}""", authority = null).statusCode() shouldBe 409
        request(
            "POST",
            "/training/application/list",
            """{"trainingId":"training-1","trainingProIds":[1]}""",
            authority = null,
        ).statusCode() shouldBe
            409
        calls.isEmpty() shouldBe true
    }

    @Test
    fun `다건 신청의 연수자 실패와 저장 실패는 전체 요청을 실패시킨다`() {
        addProgram("2026-09-25T09:00", expoId)
        val body = """{"trainingId":"training-1","trainingProIds":[1,2]}"""
        userResolveStatus = 404
        request("POST", "/training/application/list", body, authority = null).statusCode() shouldBe 404
        calls.none { it.path == "/internal/training-program-applications" } shouldBe true
        calls.clear()
        userResolveStatus = 500
        request("POST", "/training/application/list", body, authority = null).statusCode() shouldBe 502
        calls.none { it.path == "/internal/training-program-applications" } shouldBe true
        calls.clear()
        userResolveStatus = 200
        applicationCreateStatus = 409
        request("POST", "/training/application/list", body, authority = null).statusCode() shouldBe 409
        calls.count { it.path == "/internal/training-program-applications" } shouldBe 1
        calls.clear()
        applicationCreateStatus = 500
        request("POST", "/training/application/list", body, authority = null).statusCode() shouldBe 502
        calls.count { it.path == "/internal/training-program-applications" } shouldBe 1
    }

    @Test
    fun `관리자 조회 삭제 권한을 검사한다`() {
        request("GET", "/training/1", authority = null).statusCode() shouldBe 401
        request("DELETE", "/training/1", authority = "ROLE_USER").statusCode() shouldBe 403
        calls.isEmpty() shouldBe true
    }

    private fun addProgram(
        startedAt: String,
        ownerExpoId: String,
    ) {
        jdbc.update(
            """INSERT INTO tb_training_program (title,started_at,ended_at,category,expo_id) VALUES (?,?,?,?,?)""",
            "추가 연수",
            startedAt,
            "2026-09-25T10:00",
            "CHOICE",
            ownerExpoId,
        )
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
        authority?.let { builder.header("Authorization", "Bearer ${TestJwt.token(it)}") }
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
            registry.add("JWT_PUBLIC_KEY") { TestJwt.publicKeyPem }
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
