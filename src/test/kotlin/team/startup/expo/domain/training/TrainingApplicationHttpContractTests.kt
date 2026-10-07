package team.startup.expo.domain.training

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.persistence.autoconfigure.EntityScan
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.context.annotation.Bean
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
import tools.jackson.databind.json.JsonMapper
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

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
        formStatus = 200
        formBody = form("2026-09-01T00:00:00.000Z", "2026-09-30T00:00:00.000Z")
        resolveOrCreateStatus = 200
        resolveOrCreateBody = """{"traineeId":42,"created":true}"""
        replaceStatus = 204
        dependencyFault = null
        storedProgramIds = emptySet()
        fullProgramIds = emptySet()
        trackApplications = false
        TestClock.now = Instant.parse("2026-09-24T10:00:00+09:00")
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

    @Test
    fun `등록 신청은 무인증으로 Form 기간 확인 뒤 연수자를 만들고 신청을 통째로 바꾼다`() {
        addProgram("2026-09-25T09:00", expoId)
        val created = request("POST", traineePath, traineeBody("[2,1,2]"), authority = null)
        created.statusCode() shouldBe 201
        created.body() shouldBe ""
        calls.map { it.method + " " + it.path } shouldBe
            listOf(
                "GET /forms/$expoId",
                "POST /internal/trainees/resolve-or-create",
                "PUT /internal/training-program-applications/trainee/42",
            )
        val form = calls.first()
        form.query shouldBe "type=TRAINEE&applicationType=PRE"
        form.token shouldBe null
        val resolve = calls.single { it.path == "/internal/trainees/resolve-or-create" }
        resolve.token shouldBe "test-training-internal-token"
        mapper.readTree(resolve.body).toString() shouldBe
            """{"expoId":"$expoId","trainingId":"training-1","name":"홍길동","phoneNumber":"010-1234-5678",""" +
            """"informationJson":"{\"school\":\"서울\"}","personalInformationStatus":true}"""
        val replace = calls.last()
        replace.token shouldBe "test-training-internal-token"
        val replaceBody = mapper.readTree(replace.body)
        replaceBody.get("trainee").toString() shouldBe """{"id":42,"expoId":"$expoId"}"""
        val programs = replaceBody.get("programs")
        (0 until programs.size()).map { programs[it].get("id").asLong() }.toSet() shouldBe setOf(1L, 2L)
        (0 until programs.size()).forEach {
            programs[it].get("expoId").asString() shouldBe expoId
            programs[it].get("category").asString() shouldBe "CHOICE"
        }
    }

    @Test
    fun `신청 교체가 실패해도 연수자를 지우지 않고 재시도에서 같은 연수자로 다시 바꾼다`() {
        replaceStatus = 500
        request("POST", traineePath, traineeBody(), authority = null).statusCode() shouldBe 502
        replaceStatus = 409
        request("POST", traineePath, traineeBody(), authority = null).statusCode() shouldBe 409
        resolveOrCreateBody = """{"traineeId":42,"created":false}"""
        replaceStatus = 204
        request("POST", traineePath, traineeBody(), authority = null).statusCode() shouldBe 201
        calls.count { it.path == "/internal/trainees/resolve-or-create" } shouldBe 3
        calls.count { it.method == "PUT" && it.path == "/internal/training-program-applications/trainee/42" } shouldBe 3
        calls.none { it.method == "DELETE" } shouldBe true
    }

    @Test
    fun `등록 신청은 박람회 날짜와 사전 등록 시각의 양 끝을 포함한다`() {
        formBody = form("2026-09-24T01:00:00.000Z", "2026-09-24T02:00:00.000Z")
        TestClock.now = Instant.parse("2026-09-24T01:00:00Z")
        request("POST", traineePath, traineeBody(), authority = null).statusCode() shouldBe 201
        TestClock.now = Instant.parse("2026-09-24T02:00:00Z")
        request("POST", traineePath, traineeBody(), authority = null).statusCode() shouldBe 201
        calls.clear()
        TestClock.now = Instant.parse("2026-09-24T02:00:00.001Z")
        request("POST", traineePath, traineeBody(), authority = null).statusCode() shouldBe 400
        TestClock.now = Instant.parse("2026-09-24T00:59:59.999Z")
        request("POST", traineePath, traineeBody(), authority = null).statusCode() shouldBe 400
        calls.map { it.path }.toSet() shouldBe setOf("/forms/$expoId")
        calls.clear()

        formBody = form("2026-09-01T00:00:00.000Z", "2026-09-30T00:00:00.000Z")
        TestClock.now = Instant.parse("2026-09-25T23:59:59+09:00")
        request("POST", traineePath, traineeBody(), authority = null).statusCode() shouldBe 201
        calls.clear()
        TestClock.now = Instant.parse("2026-09-26T00:00:00+09:00")
        request("POST", traineePath, traineeBody(), authority = null).statusCode() shouldBe 400
        TestClock.now = Instant.parse("2026-09-23T23:59:59+09:00")
        request("POST", traineePath, traineeBody(), authority = null).statusCode() shouldBe 400
        calls.isEmpty() shouldBe true
    }

    @Test
    fun `등록 신청은 잘못된 요청 박람회 프로그램을 연수자 생성 전에 거절한다`() {
        request("POST", traineePath, traineeBody("[]"), authority = null).statusCode() shouldBe 400
        request("POST", traineePath, traineeBody("[1,null]"), authority = null).statusCode() shouldBe 400
        request("POST", traineePath, traineeBody("[1,0]"), authority = null).statusCode() shouldBe 400
        request("POST", traineePath, traineeBody("[-1]"), authority = null).statusCode() shouldBe 400
        request(
            "POST",
            traineePath,
            """{"trainingId":"training-1","name":"홍길동","informationJson":"{}",""" +
                """"personalInformationStatus":true,"trainingProIds":[1]}""",
            authority = null,
        ).statusCode() shouldBe 400
        request(
            "POST",
            traineePath,
            traineeBody().replace(""""personalInformationStatus":true""", """"personalInformationStatus":null"""),
            authority = null,
        ).statusCode() shouldBe 400
        request("POST", "/training/application/list/trainee/missing-expo", traineeBody(), authority = null).statusCode() shouldBe 404
        calls.isEmpty() shouldBe true

        request("POST", traineePath, traineeBody("[1,999]"), authority = null).statusCode() shouldBe 404
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
        request("POST", traineePath, traineeBody("[1,2]"), authority = null).statusCode() shouldBe 404
        formStatus = 404
        request("POST", traineePath, traineeBody(), authority = null).statusCode() shouldBe 404
        calls.map { it.path }.toSet() shouldBe setOf("/forms/$expoId")
        calls.clear()

        jdbc.update("UPDATE tb_expo SET deleting_at = CURRENT_TIMESTAMP WHERE id = ?", expoId)
        request("POST", traineePath, traineeBody(), authority = null).statusCode() shouldBe 409
        calls.isEmpty() shouldBe true
    }

    @Test
    fun `등록 신청은 Form과 User 실패를 상태로 보존하고 신청을 바꾸지 않는다`() {
        formStatus = 500
        request("POST", traineePath, traineeBody(), authority = null).statusCode() shouldBe 502
        formStatus = 200
        formBody = "invalid"
        request("POST", traineePath, traineeBody(), authority = null).statusCode() shouldBe 502
        formBody = form("2026-09-01T00:00:00.000Z", "2026-09-30T00:00:00.000Z")
        resolveOrCreateStatus = 400
        request("POST", traineePath, traineeBody(), authority = null).statusCode() shouldBe 400
        resolveOrCreateStatus = 409
        request("POST", traineePath, traineeBody(), authority = null).statusCode() shouldBe 409
        resolveOrCreateStatus = 500
        request("POST", traineePath, traineeBody(), authority = null).statusCode() shouldBe 502
        resolveOrCreateStatus = 200
        resolveOrCreateBody = """{"traineeId":0,"created":true}"""
        request("POST", traineePath, traineeBody(), authority = null).statusCode() shouldBe 502
        calls.none { it.method == "PUT" } shouldBe true
    }

    @Test
    fun `등록 신청 공개 허용은 해당 경로의 POST에만 적용된다`() {
        request("POST", traineePath, traineeBody(), authority = "ROLE_USER").statusCode() shouldBe 201
        request("GET", traineePath, authority = null).statusCode() shouldBe 401
        request("PUT", traineePath, traineeBody(), authority = "ROLE_ADMIN").statusCode() shouldBe 403
    }

    private val traineePath get() = "/training/application/list/trainee/$expoId"

    @Test
    fun `User 조회 입력 오류는 400이고 다중 연수 번호는 409다`() {
        val requests =
            listOf(
                "/training/application/1" to """{"trainingId":"training-1"}""",
                "/training/application/list" to """{"trainingId":"training-1","trainingProIds":[1]}""",
            )
        for ((path, body) in requests) {
            userResolveStatus = 400
            assertError(request("POST", path, body, authority = null), 400)
            userResolveStatus = 409
            assertError(request("POST", path, body, authority = null), 409)
        }
        calls.none { it.path == "/internal/training-program-applications" } shouldBe true
    }

    @Test
    fun `추가 중복은 409지만 같은 목록 교체는 201이며 정원 실패는 기존 목록을 보존한다`() {
        trackApplications = true
        addProgram("2026-09-25T09:00", expoId)
        request("POST", traineePath, traineeBody(), authority = null).statusCode() shouldBe 201
        val retry = request("POST", traineePath, traineeBody(), authority = null)
        retry.statusCode() shouldBe 201
        retry.body() shouldBe ""
        storedProgramIds shouldBe setOf(1L)
        assertError(request("POST", "/training/application/1", """{"trainingId":"training-1"}""", authority = null), 409)
        assertError(
            request("POST", "/training/application/list", """{"trainingId":"training-1","trainingProIds":[1,2]}""", authority = null),
            409,
        )
        storedProgramIds shouldBe setOf(1L)
        fullProgramIds = setOf(2L)
        calls.clear()
        assertError(request("POST", traineePath, traineeBody("[1,2]"), authority = null), 409)
        calls.map { it.method + " " + it.path } shouldBe
            listOf(
                "GET /forms/$expoId",
                "POST /internal/trainees/resolve-or-create",
                "PUT /internal/training-program-applications/trainee/42",
            )
        val replacement = mapper.readTree(calls.last().body)
        replacement.get("trainee").toString() shouldBe """{"id":42,"expoId":"$expoId"}"""
        val selected = replacement.get("programs")
        (0 until selected.size()).map { selected[it].get("id").asLong() }.toSet() shouldBe setOf(1L, 2L)
        storedProgramIds shouldBe setOf(1L)
        assertError(request("POST", "/training/application/2", """{"trainingId":"training-1"}""", authority = null), 409)
        assertError(
            request("POST", "/training/application/list", """{"trainingId":"training-1","trainingProIds":[2]}""", authority = null),
            409,
        )
        storedProgramIds shouldBe setOf(1L)
        assertError(request("POST", traineePath, traineeBody("[999]"), authority = null), 404)
        storedProgramIds shouldBe setOf(1L)
        fullProgramIds = emptySet()
        request("POST", traineePath, traineeBody("[2]"), authority = null).statusCode() shouldBe 201
        storedProgramIds shouldBe setOf(2L)
    }

    @Test
    fun `계약에 없는 공급자 4xx는 그대로 노출하지 않는다`() {
        for (status in listOf(400, 404, 401, 403, 422)) {
            applicationCreateStatus = status
            assertError(request("POST", "/training/application/1", """{"trainingId":"training-1"}""", authority = null), 502)
            replaceStatus = status
            assertError(request("POST", traineePath, traineeBody(), authority = null), 502)
        }
        resolveOrCreateStatus = 404
        assertError(request("POST", traineePath, traineeBody(), authority = null), 502)
        formStatus = 400
        assertError(request("POST", traineePath, traineeBody(), authority = null), 502)
    }

    @Test
    fun `성공 상태의 손상된 User와 Form 응답은 NPE 대신 502다`() {
        for (body in listOf("invalid", "null", "{}", """{"traineeId":0}""")) {
            userResolveBody = body
            assertError(request("POST", "/training/application/1", """{"trainingId":"training-1"}""", authority = null), 502)
            resolveOrCreateBody = body
            assertError(request("POST", traineePath, traineeBody(), authority = null), 502)
        }
        resolveOrCreateBody = """{"traineeId":42}"""
        for (body in listOf("null", "{}", """{"startDate":null,"endDate":null}""")) {
            formBody = body
            assertError(request("POST", traineePath, traineeBody(), authority = null), 502)
        }
        calls.none { it.method == "PUT" || it.path == "/internal/training-program-applications" } shouldBe true
    }

    @Test
    fun `의존 서비스 연결 단절과 응답 타임아웃은 503다`() {
        val paths =
            listOf(
                "/forms/$expoId",
                "/internal/trainees/resolve-or-create",
                "/internal/training-program-applications/trainee/42",
            )
        for (path in paths) {
            dependencyFault = path to "disconnect"
            assertError(request("POST", traineePath, traineeBody(), authority = null), 503)
        }
        dependencyFault = "/internal/trainees/resolve" to "timeout"
        timeoutRelease = CountDownLatch(1)
        val started = System.nanoTime()
        try {
            assertError(request("POST", "/training/application/1", """{"trainingId":"training-1"}""", authority = null), 503)
            (TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 8000) shouldBe true
        } finally {
            timeoutRelease.countDown()
        }
    }

    private fun assertError(
        response: HttpResponse<String>,
        status: Int,
    ) {
        response.statusCode() shouldBe status
        val error = mapper.readTree(response.body())
        error.get("status").asInt() shouldBe status
        error.get("message").asString().isNotBlank() shouldBe true
    }

    private fun traineeBody(programIds: String = "[1]") =
        """{"trainingId":"training-1","phoneNumber":"010-1234-5678","name":"홍길동",""" +
            """"informationJson":"{\"school\":\"서울\"}","personalInformationStatus":true,"trainingProIds":$programIds}"""

    private fun form(
        startDate: String,
        endDate: String,
    ) = """{"id":"f","expoId":"$expoId","participantType":"TRAINEE","applicationType":"PRE",""" +
        """"startDate":"$startDate","endDate":"$endDate","dynamicForm":[]}"""

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
            val query: String?,
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
        private var formStatus = 200
        private var formBody = ""
        private var resolveOrCreateStatus = 200
        private var resolveOrCreateBody = ""
        private var replaceStatus = 204

        @Volatile
        private var dependencyFault: Pair<String, String>? = null

        @Volatile
        private var trackApplications = false

        @Volatile
        private var storedProgramIds = emptySet<Long>()

        @Volatile
        private var fullProgramIds = emptySet<Long>()

        @Volatile
        private var timeoutRelease = CountDownLatch(0)

        private val dependencyMapper = JsonMapper.builder().build()
        private val upstreamExecutor = Executors.newCachedThreadPool()

        private val upstream =
            HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
                executor = upstreamExecutor
                createContext("/") { exchange -> respond(exchange) }
                start()
            }

        @AfterAll
        @JvmStatic
        fun stopUpstream() {
            upstream.stop(0)
            upstreamExecutor.shutdownNow()
        }

        @DynamicPropertySource
        @JvmStatic
        fun dependencyProperties(registry: DynamicPropertyRegistry) {
            registry.add("JWT_PUBLIC_KEY") { TestJwt.publicKeyPem }
            registry.add("expo.training.user-service-url") { "http://127.0.0.1:${upstream.address.port}" }
            registry.add("expo.training.application-service-url") { "http://127.0.0.1:${upstream.address.port}" }
            registry.add("expo.training.internal-token") { "test-training-internal-token" }
            registry.add("expo.form-service-url") { "http://127.0.0.1:${upstream.address.port}" }
        }

        private fun respond(exchange: HttpExchange) {
            val path = exchange.requestURI.path
            val requestBody = exchange.requestBody.readAllBytes().toString(Charsets.UTF_8)
            calls.add(
                DependencyCall(
                    path = path,
                    query = exchange.requestURI.rawQuery,
                    method = exchange.requestMethod,
                    body = requestBody,
                    token = exchange.requestHeaders.getFirst("X-Internal-Token"),
                ),
            )
            val fault = dependencyFault
            if (fault?.first == path) {
                if (fault.second == "timeout") timeoutRelease.await(30, TimeUnit.SECONDS)
                exchange.close()
                return
            }
            val programStatus = if (exchange.requestMethod == "DELETE") applicationDeleteStatus else applicationListStatus
            var status =
                when (path) {
                    "/internal/trainees/resolve" -> userResolveStatus
                    "/internal/trainees/names" -> userNamesStatus
                    "/internal/training-program-applications" -> applicationCreateStatus
                    "/internal/training-program-applications/program/1" -> programStatus
                    "/internal/trainees/resolve-or-create" -> resolveOrCreateStatus
                    "/internal/training-program-applications/trainee/42" -> replaceStatus
                    else -> if (path.startsWith("/forms/")) formStatus else 404
                }
            // Application의 원자적 추가/교체 wire 계약을 흉내 낸다. 실제 DB 롤백은 통합 보고서의 검증 범위다.
            if (trackApplications &&
                path.startsWith("/internal/training-program-applications") &&
                exchange.requestMethod in listOf("POST", "PUT")
            ) {
                val json = dependencyMapper.readTree(requestBody).get("programs")
                val selected = (0 until json.size()).map { json[it].get("id").asLong() }.toSet()
                val replacement = exchange.requestMethod == "PUT"
                status =
                    if ((!replacement && selected.any { it in storedProgramIds }) ||
                        selected.any { it in fullProgramIds && it !in storedProgramIds }
                    ) {
                        409
                    } else {
                        storedProgramIds = if (replacement) selected else storedProgramIds + selected
                        if (replacement) 204 else 201
                    }
            }
            val responseBody =
                when (path) {
                    "/internal/trainees/resolve" -> userResolveBody
                    "/internal/trainees/names" -> userNamesBody
                    "/internal/training-program-applications/program/1" -> if (exchange.requestMethod == "GET") applicationListBody else ""
                    "/internal/trainees/resolve-or-create" -> resolveOrCreateBody
                    else -> if (path.startsWith("/forms/")) formBody else ""
                }
            val bytes = responseBody.toByteArray(Charsets.UTF_8)
            exchange.sendResponseHeaders(status, if (status == 204 || bytes.isEmpty()) -1 else bytes.size.toLong())
            if (bytes.isNotEmpty() && status != 204) exchange.responseBody.write(bytes)
            exchange.close()
        }

        object TestClock : Clock() {
            @Volatile
            var now: Instant = Instant.EPOCH

            override fun getZone(): ZoneId = ZoneId.of("Asia/Seoul")

            override fun withZone(zone: ZoneId): Clock = this

            override fun instant(): Instant = now
        }

        @Container
        @ServiceConnection
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:17-alpine")
    }

    @TestConfiguration
    class ClockConfig {
        @Bean
        fun testClock(): Clock = TestClock
    }
}
