package team.startup.expo.domain.standard

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
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import team.startup.expo.domain.expo.HttpTestAuthenticationConfiguration
import tools.jackson.databind.ObjectMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

@SpringBootTest(
    webEnvironment = RANDOM_PORT,
    properties = [
        "eureka.client.enabled=false",
        "spring.jpa.hibernate.ddl-auto=validate",
    ],
)
@EntityScan("team.startup.expo.domain")
@Import(HttpTestAuthenticationConfiguration::class)
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
        @Container
        @ServiceConnection
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:17-alpine")
    }
}
