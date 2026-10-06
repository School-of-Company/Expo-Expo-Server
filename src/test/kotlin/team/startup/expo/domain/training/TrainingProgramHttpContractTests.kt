package team.startup.expo.domain.training

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
import team.startup.expo.domain.expo.entity.Expo
import team.startup.expo.domain.expo.repository.ExpoRepository
import team.startup.expo.domain.training.repository.TrainingProgramRepository
import team.startup.expo.support.TestJwt
import tools.jackson.databind.ObjectMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

@SpringBootTest(
    webEnvironment = RANDOM_PORT,
    properties = ["eureka.client.enabled=false", "spring.jpa.hibernate.ddl-auto=validate"],
)
@EntityScan("team.startup.expo.domain")
@Testcontainers
class TrainingProgramHttpContractTests {
    @LocalServerPort
    private var port: Int = 0

    @Autowired
    private lateinit var expoRepository: ExpoRepository

    @Autowired
    private lateinit var trainingProgramRepository: TrainingProgramRepository

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    private val http = HttpClient.newHttpClient()

    @BeforeEach
    fun setup() {
        jdbcTemplate.execute("TRUNCATE TABLE tb_training_program, tb_standard_program, tb_expo RESTART IDENTITY")
        expoRepository.saveAndFlush(
            Expo(
                id = "training-test-expo",
                title = "박람회",
                description = "설명",
                startedDay = "2026-09-24",
                finishedDay = "2026-09-25",
                location = "서울",
                coverImage = null,
                x = "127",
                y = "37",
                applicationPerson = 0,
                yesterdayApplicationPerson = 0,
            ),
        )
    }

    @Test
    fun `단건 생성과 목록 응답은 원본 날짜 문자열과 필드를 유지한다`() {
        val created = request("POST", "/training/training-test-expo", PROGRAM)
        val listed = request("GET", "/training/program/training-test-expo")

        created.statusCode() shouldBe 201
        created.body() shouldBe ""
        listed.statusCode() shouldBe 200
        val program = objectMapper.readTree(listed.body()).single()
        program.toString() shouldBe
            """{"id":1,"title":"연수","startedAt":"2026-09-24T10:00","endedAt":"2026-09-24T11:00","category":"ESSENTIAL"}"""
    }

    @Test
    fun `일괄 생성은 배열을 받고 수정은 경로 ID를 사용한다`() {
        request("POST", "/training/list/training-test-expo", "[$PROGRAM,${PROGRAM.replace("연수", "선택").replace("ESSENTIAL", "CHOICE")}] ")
            .statusCode() shouldBe 201
        val updated =
            request(
                "PATCH",
                "/training/1",
                """{"id":999,"title":"변경","startedAt":"2026-09-24 12:00","endedAt":"2026-09-24 13:00","category":"CHOICE"}""",
            )

        updated.statusCode() shouldBe 204
        updated.body() shouldBe ""
        val programs = objectMapper.readTree(request("GET", "/training/program/training-test-expo").body())
        programs.size() shouldBe 2
        programs[0].get("title").asString() shouldBe "변경"
        programs[0].get("startedAt").asString() shouldBe "2026-09-24T12:00"
        programs[1].get("category").asString() shouldBe "CHOICE"
    }

    @Test
    fun `빈 일괄 목록은 허용하고 잘못된 배열 요소는 저장하지 않는다`() {
        request("POST", "/training/list/training-test-expo", "[]").statusCode() shouldBe 201
        request("POST", "/training/list/training-test-expo", "[$PROGRAM,${PROGRAM.replace("ESSENTIAL", "INVALID")}]")
            .statusCode() shouldBe 400
        trainingProgramRepository.count() shouldBe 0
    }

    @Test
    fun `일괄 생성은 각 요소의 제목 길이를 검증하고 전체 요청을 거부한다`() {
        val tooLong = PROGRAM.replace("연수", "가".repeat(51))

        request("POST", "/training/list/training-test-expo", "[$PROGRAM,$tooLong]").statusCode() shouldBe 400
        trainingProgramRepository.count() shouldBe 0
    }

    @Test
    fun `DB 허용 범위인 유니코드 제목은 단건 생성과 수정에서 성공한다`() {
        val title = "😀".repeat(26)
        val created = request("POST", "/training/training-test-expo", PROGRAM.replace("연수", title))

        created.statusCode() shouldBe 201
        val updated =
            request(
                "PATCH",
                "/training/1",
                """{"id":1,"title":"$title","startedAt":"2026-09-24 12:00","endedAt":"2026-09-24 13:00","category":"CHOICE"}""",
            )
        updated.statusCode() shouldBe 204
        trainingProgramRepository.findById(1).orElseThrow().title shouldBe title
    }

    @Test
    fun `오류와 권한은 저장 전에 적용된다`() {
        request("POST", "/training/missing", PROGRAM).statusCode() shouldBe 404
        request("POST", "/training/training-test-expo", PROGRAM.replace("ESSENTIAL", "INVALID")).statusCode() shouldBe 400
        request("POST", "/training/training-test-expo", PROGRAM.replace("2026-09-24 10:00", "bad-date")).statusCode() shouldBe 400
        request("POST", "/training/training-test-expo", PROGRAM, authority = null).statusCode() shouldBe 401
        request("POST", "/training/training-test-expo", PROGRAM, authority = "ROLE_USER").statusCode() shouldBe 403
        val missingProgram =
            request(
                "PATCH",
                "/training/999",
                """{"id":1,"title":"변경","startedAt":"2026-09-24 12:00","endedAt":"2026-09-24 13:00","category":"CHOICE"}""",
            )
        missingProgram.statusCode() shouldBe 404
        trainingProgramRepository.count() shouldBe 0
    }

    private fun request(
        method: String,
        path: String,
        body: String? = null,
        authority: String? = "ROLE_ADMIN",
    ): HttpResponse<String> {
        val builder = HttpRequest.newBuilder(URI.create("http://localhost:$port$path"))
        authority?.let { builder.header("Authorization", "Bearer ${TestJwt.token(it)}") }
        val publisher = body?.let { HttpRequest.BodyPublishers.ofString(it) } ?: HttpRequest.BodyPublishers.noBody()
        body?.let { builder.header("Content-Type", "application/json") }
        return http.send(builder.method(method, publisher).build(), HttpResponse.BodyHandlers.ofString())
    }

    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun jwtPublicKey(registry: DynamicPropertyRegistry) {
            registry.add("JWT_PUBLIC_KEY") { TestJwt.publicKeyPem }
        }

        private const val PROGRAM =
            """{"title":"연수","startedAt":"2026-09-24 10:00","endedAt":"2026-09-24 11:00","category":"ESSENTIAL"}"""

        @Container
        @ServiceConnection
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:17-alpine")
    }
}
