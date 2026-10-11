package team.startup.expo.global.attendance

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
    properties = ["eureka.client.enabled=false", "spring.jpa.hibernate.ddl-auto=validate"],
)
@EntityScan("team.startup.expo.domain")
@Testcontainers
class ProgramAttendanceHttpContractTests {
    @LocalServerPort
    private var port: Int = 0

    @Autowired
    private lateinit var jdbc: JdbcTemplate

    @Autowired
    private lateinit var mapper: ObjectMapper

    @Autowired
    private lateinit var attendances: ProgramAttendanceClient

    private val http = HttpClient.newHttpClient()
    private val expoId = "attendance-contract-expo"

    private enum class Kind(
        val path: String,
        val idField: String,
        val programName: String,
    ) {
        STANDARD("standard", "participantId", "일반"),
        TRAINING("training", "traineeId", "연수"),
    }

    @BeforeEach
    fun setUp() {
        calls.clear()
        applicationBody = "[]"
        attentionStatus = 200
        attentionBody = "[]"
        attentionDrop = false
        remoteAttendances.clear()
        remoteAttendances.addAll(listOf("standard/1", "training/1", "standard/2"))
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
            "INSERT INTO tb_standard_program (title,started_at,ended_at,expo_id) VALUES (?,?,?,?)",
            "일반",
            "2026-09-24T09:00",
            "2026-09-24T10:00",
            expoId,
        )
        jdbc.update(
            "INSERT INTO tb_training_program (title,started_at,ended_at,category,expo_id) VALUES (?,?,?,?,?)",
            "연수",
            "2026-09-24T09:00",
            "2026-09-24T10:00",
            "CHOICE",
            expoId,
        )
    }

    @Test
    fun `삭제는 종류별 ID 영역과 토큰을 보존하고 장애 후 재시도한다`() {
        Kind.entries.forEach { kind ->
            val table = "tb_${kind.path}_program"
            listOf(200, 401, 403, 404, 500).forEach { status ->
                attentionStatus = status
                request("DELETE", "/${kind.path}/1").statusCode() shouldBe 502
                jdbc.queryForObject("SELECT count(*) FROM $table WHERE id=1", Long::class.java) shouldBe 1L
            }
            attentionDrop = true
            request("DELETE", "/${kind.path}/1").statusCode() shouldBe 503
            jdbc.queryForObject("SELECT count(*) FROM $table WHERE id=1", Long::class.java) shouldBe 1L
            attentionDrop = false
            attentionStatus = 204
            request("DELETE", "/${kind.path}/1").statusCode() shouldBe 204
            remoteAttendances.contains("${kind.path}/1") shouldBe false
            remoteAttendances.contains("standard/2") shouldBe true
            if (kind == Kind.STANDARD) remoteAttendances.contains("training/1") shouldBe true
            val cleanup = calls.last { it.path == "/internal/program-attendances/${kind.path}/1" }
            cleanup.method shouldBe "DELETE"
            cleanup.token shouldBe "test-attention-token"
            if (kind == Kind.STANDARD) attendances.deleteStandard(1) else attendances.deleteTraining(1)
            request("DELETE", "/${kind.path}/1").statusCode() shouldBe 404
        }
    }

    @Test
    fun `출석 설정 누락은 프로그램과 원격 신청을 보존한다`() {
        org.springframework.test.util.ReflectionTestUtils
            .setField(attendances, "internalToken", "")
        try {
            Kind.entries.forEach { kind ->
                request("DELETE", "/${kind.path}/1").statusCode() shouldBe 503
                jdbc.queryForObject("SELECT count(*) FROM tb_${kind.path}_program", Long::class.java) shouldBe 1L
            }
            calls.isEmpty() shouldBe true
        } finally {
            org.springframework.test.util.ReflectionTestUtils
                .setField(attendances, "internalToken", "test-attention-token")
        }
    }

    @Test
    fun `출석은 Attention 기록으로 합치고 신청 ID와 순서 이름 제목은 유지한다`() {
        Kind.entries.forEach { kind ->
            calls.clear()
            val id = kind.idField
            // Application이 예전 출석 필드를 보내든 안 보내든 무시한다
            applicationBody =
                """[{"applicationId":9,"$id":44,"status":false,"entryTime":null,"leaveTime":null},
                {"applicationId":7,"$id":42,"status":true,"entryTime":"08:00","leaveTime":"08:30"},
                {"applicationId":8,"$id":43}]"""
            attentionBody =
                """[{"$id":44,"entryTime":"09:00","leaveTime":"10:00"},
                {"$id":43,"entryTime":"09:05","leaveTime":null,"attendanceDate":"2026-09-24"},
                {"$id":99,"entryTime":"09:00","leaveTime":null}]"""

            val response = request("GET", "/${kind.path}/1")

            response.statusCode() shouldBe 200
            val name = kind.programName
            mapper.readTree(response.body()).toString() shouldBe
                """[{"id":9,"name":"사람44","programName":"$name","status":true,"entryTime":"09:00","leaveTime":"10:00"},""" +
                """{"id":7,"name":"사람42","programName":"$name","status":false,"entryTime":null,"leaveTime":null},""" +
                """{"id":8,"name":"사람43","programName":"$name","status":true,"entryTime":"09:05","leaveTime":null}]"""
            val attention = calls.single { it.path.startsWith("/internal/program-attendances") }
            attention.method shouldBe "GET"
            attention.path shouldBe "/internal/program-attendances/${kind.path}/1"
            attention.token shouldBe "test-attention-token"
            val names = calls.single { it.path.endsWith("/names") }
            names.token shouldBe "test-${kind.path}-token"
            mapper.readTree(names.body).get("${id}s").toString() shouldBe "[44,42,43]"
        }
    }

    @Test
    fun `출석 기록이 없으면 미출석이고 신청자가 없으면 User와 Attention을 호출하지 않는다`() {
        Kind.entries.forEach { kind ->
            calls.clear()
            applicationBody = "[]"
            request("GET", "/${kind.path}/1").body() shouldBe "[]"
            calls.none { it.path.startsWith("/internal/program-attendances") || it.path.endsWith("/names") } shouldBe true

            applicationBody = """[{"applicationId":7,"${kind.idField}":42}]"""
            attentionBody = "[]"
            val response = mapper.readTree(request("GET", "/${kind.path}/1").body())[0]
            response.get("status").asBoolean() shouldBe false
            response.get("entryTime").isNull shouldBe true
            response.get("leaveTime").isNull shouldBe true
        }
    }

    @Test
    fun `Attention 장애는 미출석으로 바꾸지 않고 오류로 응답한다`() {
        Kind.entries.forEach { kind ->
            applicationBody = """[{"applicationId":7,"${kind.idField}":42}]"""
            listOf(401, 403, 404, 500).forEach { status ->
                attentionStatus = status
                request("GET", "/${kind.path}/1").statusCode() shouldBe 502
            }
            attentionStatus = 200
            attentionDrop = true
            request("GET", "/${kind.path}/1").statusCode() shouldBe 503
            attentionDrop = false
        }
    }

    @Test
    fun `손상되거나 중복된 출석 응답은 502이고 공급자 본문을 노출하지 않는다`() {
        Kind.entries.forEach { kind ->
            val id = kind.idField
            applicationBody = """[{"applicationId":7,"$id":42}]"""
            val record = """{"$id":42,"entryTime":"09:00","leaveTime":null}"""
            listOf(
                "secret-invalid",
                "{}",
                "null",
                "[null]",
                "[$record,$record]",
                """[{"$id":0,"entryTime":"09:00","leaveTime":null}]""",
                """[{"entryTime":"09:00","leaveTime":null}]""",
                """[{"$id":42,"entryTime":"9:00","leaveTime":null}]""",
                """[{"$id":42,"entryTime":"09:00:00","leaveTime":null}]""",
                """[{"$id":42,"entryTime":"25:00","leaveTime":null}]""",
                """[{"$id":42,"entryTime":"09:00","leaveTime":"10"}]""",
                """[{"$id":42,"entryTime":null,"leaveTime":"10:00"}]""",
            ).forEach { body ->
                attentionBody = body
                val response = request("GET", "/${kind.path}/1")
                response.statusCode() shouldBe 502
                response.body().contains("secret") shouldBe false
            }
        }
    }

    private fun request(
        method: String,
        path: String,
    ): HttpResponse<String> =
        http.send(
            HttpRequest
                .newBuilder(URI.create("http://localhost:$port$path"))
                .header("Authorization", "Bearer ${TestJwt.token("ROLE_ADMIN")}")
                .method(method, HttpRequest.BodyPublishers.noBody())
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        )

    companion object {
        private data class DependencyCall(
            val path: String,
            val method: String,
            val token: String?,
            val body: String,
        )

        private val calls = ConcurrentLinkedQueue<DependencyCall>()
        private val remoteAttendances = ConcurrentLinkedQueue<String>()

        @Volatile
        private var applicationBody = "[]"

        @Volatile
        private var attentionStatus = 200

        @Volatile
        private var attentionBody = "[]"

        @Volatile
        private var attentionDrop = false

        private val upstream =
            HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
                createContext("/") { exchange -> respond(exchange) }
                start()
            }

        @DynamicPropertySource
        @JvmStatic
        fun dependencyProperties(registry: DynamicPropertyRegistry) {
            val url = "http://127.0.0.1:${upstream.address.port}"
            registry.add("JWT_PUBLIC_KEY") { TestJwt.publicKeyPem }
            registry.add("expo.standard.user-service-url") { url }
            registry.add("expo.standard.application-service-url") { url }
            registry.add("expo.standard.internal-token") { "test-standard-token" }
            registry.add("expo.training.user-service-url") { url }
            registry.add("expo.training.application-service-url") { url }
            registry.add("expo.training.internal-token") { "test-training-token" }
            registry.add("expo.attention.service-url") { url }
            registry.add("expo.attention.internal-token") { "test-attention-token" }
        }

        private fun respond(exchange: HttpExchange) {
            val path = exchange.requestURI.path
            val requestBody = exchange.requestBody.readAllBytes().toString(Charsets.UTF_8)
            calls.add(DependencyCall(path, exchange.requestMethod, exchange.requestHeaders.getFirst("X-Internal-Token"), requestBody))
            if (path.startsWith("/internal/program-attendances") && attentionDrop) {
                exchange.close()
                return
            }
            val (status, body) =
                when (path) {
                    "/internal/standard-program-applications/program/1",
                    "/internal/training-program-applications/program/1",
                    -> if (exchange.requestMethod == "DELETE") 204 to "" else 200 to applicationBody

                    "/internal/standard-participants/names" -> 200 to names(requestBody, "participantIds", "participantId")

                    "/internal/trainees/names" -> 200 to names(requestBody, "traineeIds", "traineeId")

                    "/internal/program-attendances/standard/1",
                    "/internal/program-attendances/training/1",
                    -> attentionStatus to attentionBody

                    else -> 404 to ""
                }
            val bytes = body.toByteArray(Charsets.UTF_8)
            if (exchange.requestMethod == "DELETE" &&
                status == 204
            ) {
                remoteAttendances.remove(path.removePrefix("/internal/program-attendances/"))
            }
            exchange.sendResponseHeaders(status, if (bytes.isEmpty() || status == 204) -1 else bytes.size.toLong())
            if (bytes.isNotEmpty() && status != 204) exchange.responseBody.write(bytes)
            exchange.close()
        }

        private fun names(
            requestBody: String,
            idsField: String,
            idField: String,
        ): String =
            Regex("\"$idsField\":\\[([^]]*)]")
                .find(requestBody)!!
                .groupValues[1]
                .split(",")
                .joinToString(",", "[", "]") { """{"$idField":$it,"name":"사람$it"}""" }

        @Container
        @ServiceConnection
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:17-alpine")
    }
}
