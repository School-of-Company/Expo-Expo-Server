package team.startup.expo.domain.standard

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
    properties = [
        "eureka.client.enabled=false",
        "spring.jpa.hibernate.ddl-auto=validate",
    ],
)
@EntityScan("team.startup.expo.domain")
@Testcontainers
class StandardApiPostgresHttpTests {
    @LocalServerPort
    private var port: Int = 0

    @Autowired
    private lateinit var jdbc: JdbcTemplate

    @Autowired
    private lateinit var mapper: ObjectMapper

    private val http = HttpClient.newHttpClient()
    private val expoId = "standard-contract-expo"
    private val program = """{"title":"일반","startedAt":"2026-09-24 09:00","endedAt":"2026-09-24 10:00"}"""

    @BeforeEach
    fun setUp() {
        calls.clear()
        userResolveStatus = 200
        userNamesStatus = 200
        applicationCreateStatus = 201
        applicationListStatus = 200
        applicationDeleteStatus = 204
        userResolveBody = """{"participantId":42}"""
        userNamesBody = """[{"participantId":42,"name":"홍길동"}]"""
        applicationListBody = "[]"
        jdbc.execute("TRUNCATE TABLE tb_expo_image, tb_training_program, tb_standard_program, tb_expo RESTART IDENTITY")
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
    }

    @Test
    fun `등록 일괄등록 수정과 목록 응답이 원본 계약을 따른다`() {
        request("POST", "/standard/$expoId", program).statusCode() shouldBe 201
        request("POST", "/standard/list/$expoId", "[$program,$program]").statusCode() shouldBe 201
        val list = mapper.readTree(request("GET", "/standard/program/$expoId").body())
        list.size() shouldBe 3
        list[0].get("id").asLong() shouldBe 1L
        list[0].get("startedAt").asString() shouldBe "2026-09-24T09:00"
        list[0].get("endedAt").asString() shouldBe "2026-09-24T10:00"
        list[0].size() shouldBe 4

        val update = """{"id":999,"title":"수정","startedAt":"2026-09-24 11:00","endedAt":"2026-09-24 12:00"}"""
        request("PATCH", "/standard/1", update).statusCode() shouldBe 204
        val updated = mapper.readTree(request("GET", "/standard/program/$expoId").body())[0]
        updated.get("id").asLong() shouldBe 1L
        updated.get("title").asString() shouldBe "수정"
        updated.get("startedAt").asString() shouldBe "2026-09-24T11:00"
    }

    @Test
    fun `검증 오류 없는 리소스와 권한을 구분한다`() {
        request("POST", "/standard/missing", program).statusCode() shouldBe 404
        request("POST", "/standard/$expoId", program.replace("일반", "가".repeat(51))).statusCode() shouldBe 400
        request("POST", "/standard/$expoId", program.replace("2026-09-24 09:00", "2026-09-24T09:00")).statusCode() shouldBe 400
        request("POST", "/standard/$expoId", program.replace("\"일반\"", "null")).statusCode() shouldBe 400
        request("POST", "/standard/list/$expoId", "[$program,${program.replace("일반", "가".repeat(51))}]").statusCode() shouldBe 400
        request("POST", "/standard/$expoId", program, null).statusCode() shouldBe 401
        request("POST", "/standard/$expoId", program, "ROLE_USER").statusCode() shouldBe 403
        val update = """{"id":999,"title":"수정","startedAt":"2026-09-24 11:00","endedAt":"2026-09-24 12:00"}"""
        request("PATCH", "/standard/999", update).statusCode() shouldBe 404
        request("POST", "/standard/$expoId", program).statusCode() shouldBe 201
        val invalidUpdate =
            """{"id":1,"title":"${"가".repeat(51)}","startedAt":"2026-09-24 11:00","endedAt":"2026-09-24 12:00"}"""
        request("PATCH", "/standard/1", invalidUpdate).statusCode() shouldBe 400
        mapper.readTree(request("GET", "/standard/program/$expoId").body())[0].get("title").asString() shouldBe "일반"
        jdbc.queryForObject("SELECT count(*) FROM tb_standard_program", Long::class.java) shouldBe 1L
    }

    @Test
    fun `신청은 참가자를 조회하고 중복 프로그램 ID를 한 번만 전달한다`() {
        request("POST", "/standard/$expoId", program).statusCode() shouldBe 201
        val application = request("POST", "/standard/application/$expoId", """{"phoneNumber":"01012345678","standardProIds":[1,1]}""", null)
        application.statusCode() shouldBe 201
        application.body() shouldBe ""
        val lookup = calls.single { it.path == "/internal/standard-participants/resolve" }
        mapper.readTree(lookup.body).get("expoId").asString() shouldBe expoId
        mapper.readTree(lookup.body).get("phoneNumber").asString() shouldBe "01012345678"
        lookup.token shouldBe "test-internal-token"
        val saved = calls.single { it.path == "/internal/standard-program-applications" }
        val body = mapper.readTree(saved.body)
        body.get("participant").get("id").asLong() shouldBe 42L
        body.get("programs").size() shouldBe 1
        body.get("programs")[0].get("id").asLong() shouldBe 1L
    }

    @Test
    fun `신청자 목록은 신청 ID와 출입 상태 및 null 시각을 그대로 응답한다`() {
        request("POST", "/standard/$expoId", program).statusCode() shouldBe 201
        applicationListBody =
            """
            [{"applicationId":7,"participantId":42,"status":false,"entryTime":null,"leaveTime":null},
            {"applicationId":8,"participantId":43,"status":true,"entryTime":"09:00","leaveTime":"10:00"}]
            """.trimIndent()
        userNamesBody = """[{"participantId":42,"name":"홍길동"},{"participantId":43,"name":"김영희"}]"""
        val response = request("GET", "/standard/1")
        response.statusCode() shouldBe 200
        val participants = mapper.readTree(response.body())
        participants.size() shouldBe 2
        participants[0].size() shouldBe 6
        participants[0].get("id").asLong() shouldBe 7L
        participants[0].get("name").asString() shouldBe "홍길동"
        participants[0].get("programName").asString() shouldBe "일반"
        participants[0].get("status").asBoolean() shouldBe false
        participants[0].get("entryTime").isNull shouldBe true
        participants[0].get("leaveTime").isNull shouldBe true
        participants[1].get("entryTime").asString() shouldBe "09:00"
        participants[1].get("leaveTime").asString() shouldBe "10:00"
        val names = calls.single { it.path == "/internal/standard-participants/names" }
        mapper.readTree(names.body).get("participantIds").size() shouldBe 2
    }

    @Test
    fun `신청자가 없으면 빈 배열이고 없는 프로그램은 404다`() {
        request("GET", "/standard/999").statusCode() shouldBe 404
        calls.isEmpty() shouldBe true
        request("POST", "/standard/$expoId", program).statusCode() shouldBe 201
        val empty = request("GET", "/standard/1")
        empty.statusCode() shouldBe 200
        empty.body() shouldBe "[]"
        calls.none { it.path == "/internal/standard-participants/names" } shouldBe true
        request("GET", "/standard/1", authority = null).statusCode() shouldBe 401
        request("DELETE", "/standard/1", authority = "ROLE_USER").statusCode() shouldBe 403
    }

    @Test
    fun `프로그램 삭제는 신청 기록 정리 성공 후에만 완료한다`() {
        request("POST", "/standard/$expoId", program).statusCode() shouldBe 201
        applicationDeleteStatus = 500
        request("DELETE", "/standard/1").statusCode() shouldBe 502
        jdbc.queryForObject("SELECT count(*) FROM tb_standard_program", Long::class.java) shouldBe 1L
        applicationDeleteStatus = 204
        val deleted = request("DELETE", "/standard/1")
        deleted.statusCode() shouldBe 204
        deleted.body() shouldBe ""
        jdbc.queryForObject("SELECT count(*) FROM tb_standard_program", Long::class.java) shouldBe 0L
        request("DELETE", "/standard/1").statusCode() shouldBe 404
        calls.count { it.path == "/internal/standard-program-applications/program/1" && it.method == "DELETE" } shouldBe 2
    }

    @Test
    fun `신청 오류와 행사 불일치를 구분한다`() {
        request("POST", "/standard/$expoId", program).statusCode() shouldBe 201
        userResolveStatus = 404
        request("POST", "/standard/application/$expoId", """{"phoneNumber":"missing","standardProIds":[1]}""").statusCode() shouldBe 404
        calls.none { it.path == "/internal/standard-program-applications" } shouldBe true
        userResolveStatus = 200
        applicationCreateStatus = 409
        request("POST", "/standard/application/$expoId", """{"phoneNumber":"01012345678","standardProIds":[1]}""").statusCode() shouldBe 409
        request(
            "POST",
            "/standard/application/$expoId",
            """{"phoneNumber":"01012345678","standardProIds":[999]}""",
        ).statusCode() shouldBe 404
        calls.count { it.path == "/internal/standard-program-applications" } shouldBe 1
    }

    @Test
    fun `빈 프로그램 목록은 참가자를 확인하되 신청 기록을 만들지 않는다`() {
        val response = request("POST", "/standard/application/$expoId", """{"phoneNumber":"01012345678","standardProIds":[]}""", null)
        response.statusCode() shouldBe 201
        calls.count { it.path == "/internal/standard-participants/resolve" } shouldBe 1
        calls.none { it.path == "/internal/standard-program-applications" } shouldBe true
    }

    @Test
    fun `다른 행사의 프로그램은 신청할 수 없다`() {
        jdbc.update(
            """INSERT INTO tb_expo (id,title,description,started_day,finished_day,location,x,y,application_person,yesterday_application_person)
               VALUES (?,?,?,?,?,?,?,?,?,?)""",
            "another-expo",
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
        request("POST", "/standard/another-expo", program).statusCode() shouldBe 201
        val response = request("POST", "/standard/application/$expoId", """{"phoneNumber":"01012345678","standardProIds":[1]}""")
        response.statusCode() shouldBe 404
        calls.none { it.path == "/internal/standard-program-applications" } shouldBe true
    }

    @Test
    fun `User 이름 응답이 신청 기록과 다르면 성공 목록을 만들지 않는다`() {
        request("POST", "/standard/$expoId", program).statusCode() shouldBe 201
        applicationListBody = """[{"applicationId":7,"participantId":42,"status":false,"entryTime":null,"leaveTime":null}]"""
        userNamesBody = "[]"
        request("GET", "/standard/1").statusCode() shouldBe 502
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
        @JvmStatic
        @DynamicPropertySource
        fun jwtPublicKey(registry: DynamicPropertyRegistry) {
            registry.add("JWT_PUBLIC_KEY") { TestJwt.publicKeyPem }
        }

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
        private var userResolveBody = """{"participantId":42}"""
        private var userNamesBody = """[{"participantId":42,"name":"홍길동"}]"""
        private var applicationListBody = "[]"

        private val upstream =
            HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
                createContext("/") { exchange -> respond(exchange) }
                start()
            }

        @DynamicPropertySource
        @JvmStatic
        fun dependencyProperties(registry: DynamicPropertyRegistry) {
            registry.add("expo.standard.user-service-url") { "http://127.0.0.1:${upstream.address.port}" }
            registry.add("expo.standard.application-service-url") { "http://127.0.0.1:${upstream.address.port}" }
            registry.add("expo.standard.internal-token") { "test-internal-token" }
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
                    "/internal/standard-participants/resolve" -> userResolveStatus
                    "/internal/standard-participants/names" -> userNamesStatus
                    "/internal/standard-program-applications" -> applicationCreateStatus
                    "/internal/standard-program-applications/program/1" -> programStatus
                    else -> 404
                }
            val body =
                when (path) {
                    "/internal/standard-participants/resolve" -> userResolveBody
                    "/internal/standard-participants/names" -> userNamesBody
                    "/internal/standard-program-applications/program/1" -> if (exchange.requestMethod == "GET") applicationListBody else ""
                    else -> ""
                }
            val bytes = body.toByteArray(Charsets.UTF_8)
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
