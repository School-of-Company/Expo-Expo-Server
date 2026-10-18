package team.startup.expo.domain.expo

import com.sun.net.httpserver.HttpServer
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.persistence.autoconfigure.EntityScan
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.util.ReflectionTestUtils
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import team.startup.expo.domain.expo.entity.Expo
import team.startup.expo.domain.expo.repository.ExpoRepository
import team.startup.expo.domain.expo.repository.PreregisterSessionChangeRepository
import team.startup.expo.domain.expo.repository.PreregisterSessionRepository
import team.startup.expo.domain.expo.service.ApplyPreregisterSessionChangeService
import team.startup.expo.domain.expo.service.PreregisterSessionChangesClient
import team.startup.expo.global.exception.ExpectedException
import team.startup.expo.support.TestJwt
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.sql.DriverManager
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@SpringBootTest(
    webEnvironment = RANDOM_PORT,
    properties = ["eureka.client.enabled=false", "spring.jpa.hibernate.ddl-auto=validate", "EXPO_INTERNAL_TOKEN=test-expo-token"],
)
@EntityScan("team.startup.expo.domain")
@Testcontainers
class PreregisterSessionPostgresHttpTests {
    @LocalServerPort
    private var port: Int = 0

    @Autowired
    private lateinit var jdbc: JdbcTemplate

    @Autowired
    private lateinit var mapper: ObjectMapper

    @Autowired
    private lateinit var changes: PreregisterSessionChangesClient

    @Autowired
    private lateinit var applyChange: ApplyPreregisterSessionChangeService

    @Autowired
    private lateinit var transactions: TransactionTemplate

    @Autowired
    private lateinit var expos: ExpoRepository

    @Autowired
    private lateinit var sessions: PreregisterSessionRepository

    @Autowired
    private lateinit var sessionChanges: PreregisterSessionChangeRepository

    private val http = HttpClient.newHttpClient()

    @BeforeEach
    fun setUp() {
        calls.clear()
        pending.clear()
        histories.clear()
        forcedStatus = null
        responseHook = null
        reconciliationHook = { true }
        loseReconciliationResponse = false
        ReflectionTestUtils.setField(changes, "applicationUrl", applicationUrl)
        ReflectionTestUtils.setField(changes, "internalToken", "test-application-token")
        jdbc.execute("TRUNCATE TABLE tb_expo RESTART IDENTITY CASCADE")
        expos.saveAllAndFlush(
            listOf(EXPO_ID, OTHER_EXPO_ID).map { id ->
                Expo(id, "박람회", "설명", "2026-10-31", "2026-11-01", "광주", null, "127", "37", 5, 3)
            },
        )
    }

    @Test
    fun `기존 PostgreSQL 회차 마이그레이션은 취소 마감을 추론하지 않는다`() {
        val schema = "cancel_deadline_upgrade"

        fun migrate(target: String) =
            Flyway
                .configure()
                .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
                .schemas(schema)
                .defaultSchema(schema)
                .target(target)
                .load()
                .migrate()
        try {
            migrate("28")
            DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
                connection.schema = schema
                connection.createStatement().use { statement ->
                    statement.executeUpdate(
                        """INSERT INTO tb_expo (id,title,description,started_day,finished_day,location,x,y,application_person,yesterday_application_person)
                           VALUES ('$EXPO_ID','expo','description','2026-10-31','2026-11-01','place','127','37',0,0)""",
                    )
                    statement.executeUpdate(
                        """INSERT INTO tb_preregister_session (expo_id,title,started_at,ended_at,place,capacity,waiting_capacity,closed)
                           VALUES ('$EXPO_ID','session','2026-10-31T00:30:00Z','2026-10-31T03:30:00Z','place',100,10,false)""",
                    )
                }
            }
            migrate("29")
            DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
                connection.schema = schema
                connection.createStatement().use { statement ->
                    statement.executeQuery("SELECT cancel_ended_at, revision FROM tb_preregister_session").use { rows ->
                        rows.next() shouldBe true
                        rows.getObject("cancel_ended_at") shouldBe null
                        rows.getLong("revision") shouldBe 1L
                        rows.next() shouldBe false
                    }
                }
            }
        } finally {
            jdbc.execute("DROP SCHEMA IF EXISTS $schema CASCADE")
        }
    }

    @Test
    fun `취소 마감은 미설정이면 null이고 과거 현재 미래 및 회차와 독립된 시각을 보존한다`() {
        val legacy = create()
        read(legacy).get("cancelEndedAt").isNull shouldBe true
        mapper
            .readTree(request(internalPath(legacy), token = "test-expo-token", role = null).body())
            .get("cancelEndedAt")
            .isNull shouldBe true
        val now = Instant.now()
        listOf(now.minusSeconds(60), now, now.plusSeconds(60), Instant.parse("2026-11-02T00:00:00Z")).forEach { deadline ->
            val id = create(withDeadline(deadline.toString()))
            val internal = mapper.readTree(request(internalPath(id), token = "test-expo-token", role = null).body())
            Instant.parse(internal.get("cancelEndedAt").asString()) shouldBe deadline.truncatedTo(java.time.temporal.ChronoUnit.MICROS)
        }
        listOf("\"2026-10-31T09:30:00\"", "\"invalid\"", "123", "\"123\"", "\"\"", "true").forEach { invalid ->
            val definition = DEFINITION.dropLast(1) + ",\"cancelEndedAt\":$invalid}"
            request(path(), "POST", definition).statusCode() shouldBe 400
            request(path(legacy), "PATCH", updateBody(definition = definition)).statusCode() shouldBe 400
        }
        calls.size shouldBe 0
    }

    @Test
    fun `취소 마감 설정 변경 제거는 정의 승인과 버전을 따르고 거절은 원본을 보존한다`() {
        val id = create()
        val deadline = "2026-10-30T09:30:00.123456789+09:00"
        val body = updateBody(definition = withDeadline(deadline))
        histories += id
        request(path(id), "PATCH", body).statusCode() shouldBe 409
        read(id).get("cancelEndedAt").isNull shouldBe true
        read(id).get("revision").asLong() shouldBe 1L
        histories -= id
        request(path(id), "PATCH", body).statusCode() shouldBe 204
        request(path(id), "PATCH", body).statusCode() shouldBe 204
        read(id).get("revision").asLong() shouldBe 2L
        Instant.parse(read(id).get("cancelEndedAt").asString()) shouldBe Instant.parse("2026-10-30T00:30:00.123456Z")
        val command = mapper.readTree(calls.last().body)
        command.get("definitionChanged").asBoolean() shouldBe true
        command.get("definition").get("cancelEndedAt") shouldBe read(id).get("cancelEndedAt")
        request(path(id), "PATCH", updateBody(definition = DEFINITION)).statusCode() shouldBe 409
        request(path(id), "PATCH", updateBody(revision = 2, definition = withDeadline("2026-10-30T01:00:00Z"))).statusCode() shouldBe 204
        request(
            path(id),
            "PATCH",
            updateBody(revision = 3, definition = DEFINITION.dropLast(1) + ",\"cancelEndedAt\":null}"),
        ).statusCode() shouldBe
            204
        read(id).get("cancelEndedAt").isNull shouldBe true
        read(id).get("revision").asLong() shouldBe 4L
        request(path(id), "PATCH", updateBody(revision = 4, definition = withDeadline(deadline))).statusCode() shouldBe 204
        request(path(id), "PATCH", updateBody(revision = 5, definition = DEFINITION)).statusCode() shouldBe 204
        read(id).get("cancelEndedAt").isNull shouldBe true
    }

    @Test
    fun `같은 버전 취소 마감 동시 설정은 한 건만 승인한다`() {
        val id = create()
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val futures =
                listOf("2026-10-30T00:00:00Z", "2026-10-30T01:00:00Z").map { deadline ->
                    CompletableFuture.supplyAsync({
                        start.await()
                        request(path(id), "PATCH", updateBody(definition = withDeadline(deadline))).statusCode()
                    }, executor)
                }
            start.countDown()
            futures.map { it.get(30, TimeUnit.SECONDS) }.sorted() shouldBe listOf(204, 409)
            read(id).get("revision").asLong() shouldBe 2L
            calls.size shouldBe 1
            mapper.readTree(calls.single().body).get("definition").get("cancelEndedAt") shouldBe read(id).get("cancelEndedAt")
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `관리자 생성과 내부 조회는 정의만 반환하고 시간을 같은 순간으로 보존한다`() {
        val created = request(path(), "POST", DEFINITION)
        created.statusCode() shouldBe 201
        val body = mapper.readTree(created.body())
        val id = body.get("id").asLong()
        body.get("expoId").asString() shouldBe EXPO_ID
        body.get("revision").asLong() shouldBe 1L
        body.get("waitingCapacity").asInt() shouldBe 10
        body.get("closed").asBoolean() shouldBe false
        Instant.parse(body.get("startedAt").asString()) shouldBe Instant.parse("2026-10-31T00:30:00Z")
        body.has("confirmedCount") shouldBe false
        body.has("waitingCount") shouldBe false
        body.has("participants") shouldBe false
        val internal = request(internalPath(id), token = "test-expo-token", role = null)
        internal.statusCode() shouldBe 200
        mapper.readTree(internal.body()) shouldBe body
        mapper.readTree(request(path(id)).body()) shouldBe body
        mapper.readTree(request(internalPath(), token = "test-expo-token", role = null).body()).size() shouldBe 1
        expos.findById(EXPO_ID).orElseThrow().let {
            it.applicationPerson shouldBe 5L
            it.yesterdayApplicationPerson shouldBe 3L
        }
        calls.size shouldBe 0
    }

    @Test
    fun `회차 목록은 시간과 ID로 정렬되고 빈 목록과 없는 박람회를 구분한다`() {
        request(path(OTHER_EXPO_ID), role = "ROLE_ADMIN").body() shouldBe "[]"
        val later = create(DEFINITION.replace("09:30", "14:30").replace("12:30", "17:30"))
        val first = create()
        val sameTime = create()
        val response = request(path())
        response.statusCode() shouldBe 200
        mapper.readTree(response.body()).toList().map { it.get("id").asLong() } shouldBe listOf(first, sameTime, later)
        request(path("01900000-0000-7000-8000-000000000099")).statusCode() shouldBe 404
    }

    @Test
    fun `참가자용 회차 목록은 비로그인으로 정렬된 공개 필드만 반환하고 없거나 삭제 중인 박람회는 404다`() {
        val public = path() + "/public"
        request(path(OTHER_EXPO_ID) + "/public", role = null).let {
            it.statusCode() shouldBe 200
            it.body() shouldBe "[]"
        }
        val later = create(withDeadline("2026-10-30T15:00:00Z").replace("09:30", "14:30").replace("12:30", "17:30"))
        val first = create()
        request(path(first), "PATCH", updateBody()).statusCode() shouldBe 204
        forcedStatus = 503
        request(path(later) + "?revision=1", "DELETE").statusCode() shouldBe 502
        forcedStatus = null

        val response = request(public, role = null)
        response.statusCode() shouldBe 200
        val body = mapper.readTree(response.body()).toList()
        body.map { it.get("id").asLong() } shouldBe listOf(first, later)
        body.forEach {
            it.propertyNames().toSet() shouldBe
                setOf(
                    "id",
                    "title",
                    "startedAt",
                    "endedAt",
                    "place",
                    "closed",
                    "cancelEndedAt",
                )
        }
        body[0].get("closed").asBoolean() shouldBe true
        body[0].get("cancelEndedAt").isNull shouldBe true
        body[1].get("title").asString() shouldBe "오전 회차"
        body[1].get("startedAt").asString() shouldBe "2026-10-31T05:30:00Z"
        body[1].get("cancelEndedAt").asString() shouldBe "2026-10-30T15:00:00Z"
        request(public, role = "ROLE_USER").statusCode() shouldBe 200
        request(path(), role = null).statusCode() shouldBe 401
        request(path(first), role = null).statusCode() shouldBe 401

        request(path("01900000-0000-7000-8000-000000000099") + "/public", role = null).statusCode() shouldBe 404
        markExpoDeleting()
        request(public, role = null).statusCode() shouldBe 404
    }

    @Test
    fun `관리자 CRUD는 JWT 권한을 검사하고 내부 조회는 토큰만으로 보호한다`() {
        val id = create()
        listOf("GET" to "", "POST" to DEFINITION, "PATCH" to updateBody(), "DELETE" to "").forEach { (method, body) ->
            val target = if (method == "POST") path() else path(id) + if (method == "DELETE") "?revision=1" else ""
            request(target, method, body, role = null).statusCode() shouldBe 401
            request(target, method, body, role = "ROLE_USER").statusCode() shouldBe 403
        }
        request(path(), role = null).statusCode() shouldBe 401
        listOf(internalPath(), internalPath(id)).forEach { target ->
            request(target, role = null).statusCode() shouldBe 401
            request(target, role = "ROLE_ADMIN").statusCode() shouldBe 401
            request(target, role = null, token = "wrong").statusCode() shouldBe 401
        }
        request("/pre-register/$EXPO_ID/sessions/$id", role = null).statusCode() shouldBe 401
        calls.size shouldBe 0
    }

    @Test
    fun `정원 대기정원 필수필드와 날짜 및 중첩 수정 입력을 검증한다`() {
        listOf(
            DEFINITION.replace("\"capacity\":100", "\"capacity\":0"),
            DEFINITION.replace("\"waitingCapacity\":10", "\"waitingCapacity\":-1"),
            DEFINITION.replace("\"title\":\"오전 회차\"", "\"title\":\" \""),
            DEFINITION.replace("오전 회차", "가".repeat(51)),
            DEFINITION.replace("\"place\":\"광주\"", "\"place\":\"\""),
            DEFINITION.replace("12:30", "09:30"),
            DEFINITION.replace("12:30", "08:30"),
            DEFINITION.replace("+09:00", ""),
            DEFINITION.replace("\"waitingCapacity\":10,", ""),
            DEFINITION.replace(",\"closed\":false", ""),
            DEFINITION.replace("\"capacity\":100", "\"capacity\":2147483648"),
        ).forEach { invalid -> request(path(), "POST", invalid).statusCode() shouldBe 400 }
        val id = create()
        request(path(id), "PATCH", updateBody(definition = DEFINITION.replace("\"capacity\":100", "\"capacity\":0"))).statusCode() shouldBe
            400
        request(path(id), "PATCH", updateBody(revision = 0)).statusCode() shouldBe 400
        request(path(id), "DELETE").statusCode() shouldBe 400
        request(path(id) + "?revision=0", "DELETE").statusCode() shouldBe 400
        calls.size shouldBe 0
    }

    @Test
    fun `구분 정원은 박람회 기본값으로 초기화하고 박람회별로 격리하며 기존 회차를 보존한다`() {
        val legacy = create()
        setDefaults(EXPO_ID, 300, 1000)
        setDefaults(OTHER_EXPO_ID, 5, 0)
        val withoutCapacity = DEFINITION.replace("\"capacity\":100,", "")
        val defaulted = create(withoutCapacity)
        read(defaulted).let {
            it.get("capacity").asInt() shouldBe 1300
            it.get("teacherCapacity").asInt() shouldBe 300
            it.get("nonTeacherCapacity").asInt() shouldBe 1000
        }
        val other = mapper.readTree(request(path(OTHER_EXPO_ID), "POST", withoutCapacity).body())
        other.get("capacity").asInt() shouldBe 5
        other.get("teacherCapacity").asInt() shouldBe 5
        other.get("nonTeacherCapacity").asInt() shouldBe 0
        val explicit = create(DEFINITION.dropLast(1) + ",\"teacherCapacity\":40,\"nonTeacherCapacity\":60}")
        read(explicit).get("teacherCapacity").asInt() shouldBe 40
        read(legacy).let {
            it.get("capacity").asInt() shouldBe 100
            it.get("teacherCapacity").isNull shouldBe true
            it.get("nonTeacherCapacity").isNull shouldBe true
        }
        listOf(
            DEFINITION,
            DEFINITION.dropLast(1) + ",\"teacherCapacity\":40}",
            DEFINITION.dropLast(1) + ",\"teacherCapacity\":40,\"nonTeacherCapacity\":61}",
            DEFINITION.dropLast(1) + ",\"teacherCapacity\":-1,\"nonTeacherCapacity\":101}",
            withoutCapacity.dropLast(1) + ",\"teacherCapacity\":0,\"nonTeacherCapacity\":0}",
            withoutCapacity.dropLast(1) + ",\"teacherCapacity\":2147483647,\"nonTeacherCapacity\":1}",
        ).forEach { invalid -> request(path(), "POST", invalid).statusCode() shouldBe 400 }
        setDefaults(EXPO_ID, null, null)
        request(path(), "POST", withoutCapacity).statusCode() shouldBe 400
        read(defaulted).get("teacherCapacity").asInt() shouldBe 300

        val revised = withoutCapacity.dropLast(1) + ",\"teacherCapacity\":350,\"nonTeacherCapacity\":1000}"
        request(path(defaulted), "PATCH", updateBody(definition = revised)).statusCode() shouldBe 204
        read(defaulted).let {
            it.get("capacity").asInt() shouldBe 1350
            it.get("teacherCapacity").asInt() shouldBe 350
            it.get("revision").asLong() shouldBe 2L
        }
        val command = mapper.readTree(calls.last().body)
        command.get("definitionChanged").asBoolean() shouldBe true
        command.get("definition").get("teacherCapacity").asInt() shouldBe 350
        command.get("definition").get("nonTeacherCapacity").asInt() shouldBe 1000
        request(path(defaulted), "PATCH", updateBody(revision = 2, definition = withoutCapacity.replace("false", "true")))
            .statusCode() shouldBe 204
        read(defaulted).get("teacherCapacity").asInt() shouldBe 350
        request(path(defaulted), "PATCH", updateBody(revision = 3, definition = DEFINITION)).statusCode() shouldBe 400
        request(
            path(legacy),
            "PATCH",
            updateBody(definition = DEFINITION.dropLast(1) + ",\"teacherCapacity\":30,\"nonTeacherCapacity\":70}"),
        ).statusCode() shouldBe 204
        read(legacy).get("teacherCapacity").asInt() shouldBe 30
        mapper.readTree(request(path() + "/public", role = null).body()).forEach {
            it.has("teacherCapacity") shouldBe false
            it.has("nonTeacherCapacity") shouldBe false
        }
        assertThrows(DataIntegrityViolationException::class.java) {
            jdbc.update("UPDATE tb_preregister_session SET capacity=capacity+1 WHERE id=?", explicit)
        }
        assertThrows(DataIntegrityViolationException::class.java) {
            jdbc.update("UPDATE tb_preregister_session SET non_teacher_capacity=NULL WHERE id=?", explicit)
        }
        assertThrows(DataIntegrityViolationException::class.java) {
            jdbc.update("UPDATE tb_expo SET preregister_teacher_capacity=1 WHERE id=?", EXPO_ID)
        }
    }

    @Test
    fun `다른 박람회 회차는 모든 단건 API에서 접근할 수 없다`() {
        val id = create()
        request(path(id, OTHER_EXPO_ID)).statusCode() shouldBe 404
        request(internalPath(id, OTHER_EXPO_ID), role = null, token = "test-expo-token").statusCode() shouldBe 404
        request(path(id, OTHER_EXPO_ID), "PATCH", updateBody()).statusCode() shouldBe 404
        request(path(id, OTHER_EXPO_ID) + "?revision=1", "DELETE").statusCode() shouldBe 404
        request(path(id)).statusCode() shouldBe 200
        calls.size shouldBe 0
    }

    @Test
    fun `신청 이력이 있는 회차는 정의수정과 삭제를 막고 마감 재개는 허용한다`() {
        val id = create()
        histories += id
        request(path(id), "PATCH", updateBody(definition = DEFINITION.replace("100", "101"))).statusCode() shouldBe 409
        request(path(id) + "?revision=1", "DELETE").statusCode() shouldBe 409
        request(path(id), "PATCH", updateBody()).statusCode() shouldBe 204
        val closed = read(id)
        closed.get("closed").asBoolean() shouldBe true
        closed.get("revision").asLong() shouldBe 2L
        request(path(id), "PATCH", updateBody(revision = 2, definition = DEFINITION)).statusCode() shouldBe 204
        read(id).get("closed").asBoolean() shouldBe false
        calls.map { it.operation } shouldBe listOf("UPDATE", "DELETE", "UPDATE", "UPDATE")
        mapper.readTree(calls[2].body).get("definitionChanged").asBoolean() shouldBe false
    }

    @Test
    fun `신청 없는 정의는 승인 후 교체하고 같은 요청 재전송은 버전과 호출을 늘리지 않는다`() {
        val id = create()
        val body = updateBody(definition = DEFINITION.replace("오전 회차", "변경 회차"))
        request(path(id), "PATCH", body).statusCode() shouldBe 204
        request(path(id), "PATCH", body).statusCode() shouldBe 204
        read(id).get("title").asString() shouldBe "변경 회차"
        read(id).get("revision").asLong() shouldBe 2L
        calls.size shouldBe 1
        calls.single().path shouldBe "/internal/expos/$EXPO_ID/preregister-sessions/$id/changes/2"
        calls.single().token shouldBe "test-application-token"
        calls.single().method shouldBe "PUT"
        val command = mapper.readTree(calls.single().body)
        command.get("definitionChanged").asBoolean() shouldBe true
        command.get("definition").get("revision").asLong() shouldBe 2L
        request(path(id), "PATCH", updateBody()).statusCode() shouldBe 409
        request(path(id) + "?revision=1", "DELETE").statusCode() shouldBe 409
        calls.size shouldBe 1
    }

    @Test
    fun `소수 초 시간은 PostgreSQL 정밀도로 정규화하여 같은 수정 재시도를 허용한다`() {
        val fractional = DEFINITION.replace("09:30:00", "09:30:00.123456789")
        val id = create(fractional)
        val body = updateBody(definition = fractional.replace("false", "true"))
        request(path(id), "PATCH", body).statusCode() shouldBe 204
        request(path(id), "PATCH", body).statusCode() shouldBe 204
        Instant.parse(read(id).get("startedAt").asString()).nano shouldBe 123456000
        calls.size shouldBe 1
    }

    @Test
    fun `변경 연동 미설정 비정상 응답과 연결 장애는 로컬 정의를 보존한다`() {
        val id = create()
        val original = read(id)
        listOf("applicationUrl", "internalToken").forEach { field ->
            val deleteId = create()
            val saved = ReflectionTestUtils.getField(changes, field)
            try {
                ReflectionTestUtils.setField(changes, field, "")
                request(path(id), "PATCH", updateBody()).statusCode() shouldBe 503
                request(path(deleteId) + "?revision=1", "DELETE").statusCode() shouldBe 503
            } finally {
                ReflectionTestUtils.setField(changes, field, saved)
            }
        }
        listOf(200, 201, 400, 401, 404, 500, 503).forEach { status ->
            forcedStatus = status
            request(path(id), "PATCH", updateBody()).statusCode() shouldBe 502
            read(id) shouldBe original
        }
        forcedStatus = null
        val deleteId = create()
        HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).let { unavailable ->
            unavailable.start()
            val url = "http://127.0.0.1:${unavailable.address.port}"
            unavailable.stop(0)
            ReflectionTestUtils.setField(changes, "applicationUrl", url)
            request(path(deleteId) + "?revision=1", "DELETE").statusCode() shouldBe 503
        }
        read(id) shouldBe original
    }

    @Test
    fun `원격 승인 후 DB 저장이 실패해도 원래 명령을 본문 없이 재시도한다`() {
        val id = create()
        val body = updateBody(definition = withDeadline("2026-10-30T00:00:00Z"))
        try {
            jdbc.execute(
                """CREATE FUNCTION fail_session_update() RETURNS trigger AS ${'$'}trigger${'$'}
                   BEGIN RAISE EXCEPTION 'forced session update failure'; END;
                   ${'$'}trigger${'$'} LANGUAGE plpgsql""",
            )
            jdbc.execute(
                "CREATE TRIGGER fail_session_update BEFORE UPDATE ON tb_preregister_session FOR EACH ROW EXECUTE FUNCTION fail_session_update()",
            )
            request(path(id), "PATCH", body).statusCode() shouldBe 500
        } finally {
            jdbc.execute("DROP TRIGGER IF EXISTS fail_session_update ON tb_preregister_session")
            jdbc.execute("DROP FUNCTION IF EXISTS fail_session_update()")
        }
        read(id).get("revision").asLong() shouldBe 1L
        read(id).get("cancelEndedAt").isNull shouldBe true
        pending.size shouldBe 1
        journalCount(id) shouldBe 1L
        request(path(id), "PATCH", updateBody(definition = DEFINITION.replace("100", "101"))).statusCode() shouldBe 409
        request(path(id) + "/changes/retry", "POST").statusCode() shouldBe 204
        read(id).get("revision").asLong() shouldBe 2L
        read(id).get("cancelEndedAt").asString() shouldBe "2026-10-30T00:00:00Z"
        journalCount(id) shouldBe 0L
        calls.size shouldBe 2
        calls[0].body shouldBe calls[1].body
    }

    @Test
    fun `무변경 PATCH는 공급자 없이 버전을 유지하고 최대 버전 입력은 거절한다`() {
        val id = create()
        ReflectionTestUtils.setField(changes, "applicationUrl", "")
        request(path(id), "PATCH", updateBody(definition = DEFINITION)).statusCode() shouldBe 204
        read(id).get("revision").asLong() shouldBe 1L
        request(path(id), "PATCH", updateBody(revision = Long.MAX_VALUE)).statusCode() shouldBe 400
        request(path(id), "PATCH", updateBody(revision = Long.MAX_VALUE - 1)).statusCode() shouldBe 409
        request(path(id) + "?revision=${Long.MAX_VALUE}", "DELETE").statusCode() shouldBe 400
        journalCount(id) shouldBe 0L
        calls.size shouldBe 0
    }

    @Test
    fun `재시도는 관리자 권한과 박람회 소유권을 검사한다`() {
        val id = create()
        request(path(id) + "/changes/retry", "POST", role = null).statusCode() shouldBe 401
        request(path(id) + "/changes/retry", "POST", role = "ROLE_USER").statusCode() shouldBe 403
        request(path(id, OTHER_EXPO_ID) + "/changes/retry", "POST").statusCode() shouldBe 404
        request(path(id) + "/changes/retry", "POST").statusCode() shouldBe 204
        calls.size shouldBe 0
    }

    @Test
    fun `원격 응답 대기 중에도 같은 박람회 다른 회차를 생성할 수 있다`() {
        val id = create()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        responseHook = { _, _ ->
            entered.countDown()
            check(release.await(4, TimeUnit.SECONDS))
        }
        val executor = Executors.newFixedThreadPool(2)
        try {
            val update = CompletableFuture.supplyAsync({ request(path(id), "PATCH", updateBody()).statusCode() }, executor)
            check(entered.await(4, TimeUnit.SECONDS))
            journalCount(id) shouldBe 1L
            val create = CompletableFuture.supplyAsync({ request(path(), "POST", DEFINITION).statusCode() }, executor)
            create.get(3, TimeUnit.SECONDS) shouldBe 201
            request(path(id) + "?revision=1", "DELETE").statusCode() shouldBe 409
            release.countDown()
            update.get(5, TimeUnit.SECONDS) shouldBe 204
            journalCount(id) shouldBe 0L
        } finally {
            release.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `삭제 DB 실패도 저장된 명령으로 복구하고 반복 재시도는 안전하다`() {
        val id = create()
        try {
            jdbc.execute(
                """CREATE FUNCTION fail_session_delete() RETURNS trigger AS ${'$'}trigger${'$'}
                   BEGIN RAISE EXCEPTION 'forced session delete failure'; END;
                   ${'$'}trigger${'$'} LANGUAGE plpgsql""",
            )
            jdbc.execute(
                "CREATE TRIGGER fail_session_delete BEFORE DELETE ON tb_preregister_session FOR EACH ROW EXECUTE FUNCTION fail_session_delete()",
            )
            request(path(id) + "?revision=1", "DELETE").statusCode() shouldBe 500
        } finally {
            jdbc.execute("DROP TRIGGER IF EXISTS fail_session_delete ON tb_preregister_session")
            jdbc.execute("DROP FUNCTION IF EXISTS fail_session_delete()")
        }
        journalCount(id) shouldBe 1L
        read(id).get("revision").asLong() shouldBe 1L
        request(path(id) + "/changes/retry", "POST").statusCode() shouldBe 204
        request(path(id)).statusCode() shouldBe 404
        journalCount(id) shouldBe 0L
        calls.size shouldBe 2
        calls[0].body shouldBe calls[1].body
        request(path(id) + "/changes/retry", "POST").statusCode() shouldBe 204
    }

    @Test
    fun `늦게 도착한 이전 거절 응답은 새 변경 명령을 제거하지 않는다`() {
        val id = create()
        histories += id
        val firstEntered = CountDownLatch(1)
        val secondEntered = CountDownLatch(1)
        val newEntered = CountDownLatch(1)
        val releaseOld = CountDownLatch(1)
        val releaseNew = CountDownLatch(1)
        val sequence = AtomicInteger()
        responseHook = { _, _ ->
            when (sequence.incrementAndGet()) {
                1 -> {
                    firstEntered.countDown()
                    check(secondEntered.await(4, TimeUnit.SECONDS))
                }

                2 -> {
                    secondEntered.countDown()
                    check(releaseOld.await(4, TimeUnit.SECONDS))
                }

                3 -> {
                    newEntered.countDown()
                    check(releaseNew.await(4, TimeUnit.SECONDS))
                }
            }
        }
        val executor = Executors.newFixedThreadPool(3)
        try {
            val denied = updateBody(definition = DEFINITION.replace("100", "101"))
            val first = CompletableFuture.supplyAsync({ request(path(id), "PATCH", denied).statusCode() }, executor)
            check(firstEntered.await(4, TimeUnit.SECONDS))
            val second = CompletableFuture.supplyAsync({ request(path(id), "PATCH", denied).statusCode() }, executor)
            first.get(4, TimeUnit.SECONDS) shouldBe 409
            journalCount(id) shouldBe 0L
            val accepted = CompletableFuture.supplyAsync({ request(path(id), "PATCH", updateBody()).statusCode() }, executor)
            check(newEntered.await(4, TimeUnit.SECONDS))
            releaseOld.countDown()
            second.get(4, TimeUnit.SECONDS) shouldBe 409
            journalCount(id) shouldBe 1L
            releaseNew.countDown()
            accepted.get(4, TimeUnit.SECONDS) shouldBe 204
            read(id).get("closed").asBoolean() shouldBe true
            journalCount(id) shouldBe 0L
        } finally {
            releaseOld.countDown()
            releaseNew.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `같은 버전의 동시 수정은 한 건만 성공하며 공급자 승인도 한 번만 요청한다`() {
        val id = create()
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val futures =
                listOf("회차 A", "회차 B").map { title ->
                    CompletableFuture.supplyAsync({
                        start.await()
                        request(path(id), "PATCH", updateBody(definition = DEFINITION.replace("오전 회차", title))).statusCode()
                    }, executor)
                }
            start.countDown()
            futures.map { it.get(30, TimeUnit.SECONDS) }.sorted() shouldBe listOf(204, 409)
            read(id).get("revision").asLong() shouldBe 2L
            calls.size shouldBe 1
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `부모 삭제 잠금을 기다린 회차 생성과 수정은 최신 삭제표식을 확인한다`() {
        val id = create()
        val locked = CountDownLatch(1)
        val release = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(3)
        try {
            val deletion =
                CompletableFuture.runAsync({
                    transactions.executeWithoutResult {
                        expos.findLockedById(EXPO_ID)!!.markDeleting()
                        locked.countDown()
                        check(release.await(10, TimeUnit.SECONDS))
                    }
                }, executor)
            check(locked.await(10, TimeUnit.SECONDS))
            val create = CompletableFuture.supplyAsync({ request(path(), "POST", DEFINITION).statusCode() }, executor)
            val update = CompletableFuture.supplyAsync({ request(path(id), "PATCH", updateBody()).statusCode() }, executor)
            release.countDown()
            deletion.get(15, TimeUnit.SECONDS)
            create.get(15, TimeUnit.SECONDS) shouldBe 409
            update.get(15, TimeUnit.SECONDS) shouldBe 409
        } finally {
            release.countDown()
            executor.shutdownNow()
        }
        request(internalPath(), role = null, token = "test-expo-token").statusCode() shouldBe 409
        request(internalPath(id), role = null, token = "test-expo-token").statusCode() shouldBe 409
        request(path(id) + "?revision=1", "DELETE").statusCode() shouldBe 409
        calls.size shouldBe 0
    }

    @Test
    fun `삭제는 승인된 회차만 제거하고 반복 요청은 안전하며 부모 FK도 회차를 정리한다`() {
        val id = create()
        val keep = create(expoId = OTHER_EXPO_ID)
        request(path(id) + "?revision=1", "DELETE").statusCode() shouldBe 204
        request(path(id) + "?revision=1", "DELETE").statusCode() shouldBe 204
        calls.size shouldBe 1
        calls.single().operation shouldBe "DELETE"
        mapper.readTree(calls.single().body).get("definition").isNull shouldBe true
        request(path(id)).statusCode() shouldBe 404
        request(path(keep, OTHER_EXPO_ID)).statusCode() shouldBe 200
        create()
        expos.deleteById(EXPO_ID)
        sessions.count() shouldBe 1L
    }

    @Test
    fun `PostgreSQL은 회차 정원 기간과 부모 관계를 직접 변경해도 보호한다`() {
        val id = create()
        listOf("capacity=0", "waiting_capacity=-1", "ended_at=started_at", "revision=0").forEach { invalid ->
            assertThrows(
                DataIntegrityViolationException::class.java,
            ) { jdbc.update("UPDATE tb_preregister_session SET $invalid WHERE id=?", id) }
        }
        assertThrows(DataIntegrityViolationException::class.java) {
            jdbc.update("UPDATE tb_preregister_session SET expo_id=? WHERE id=?", "01900000-0000-7000-8000-000000000099", id)
        }
    }

    @Test
    fun `an apply replay after another request committed still confirms completion`() {
        val id = create()
        request(path(id), "PATCH", updateBody()).statusCode() shouldBe 204
        reconciliationHook = { false }
        assertThrows(ExpectedException::class.java) {
            applyChange.execute(EXPO_ID, id, UUID.randomUUID())
        }.status shouldBe org.springframework.http.HttpStatus.CONFLICT
        calls.size shouldBe 1
    }

    @Test
    fun `lost completion response is recovered without applying a change twice`() {
        val id = create()
        loseReconciliationResponse = true
        request(path(id), "PATCH", updateBody()).statusCode() shouldBe 503
        read(id).get("revision").asLong() shouldBe 2L
        pending.size shouldBe 0
        loseReconciliationResponse = false
        request(path(id), "PATCH", updateBody()).statusCode() shouldBe 204
        request(path(id) + "/changes/retry", "POST").statusCode() shouldBe 204
        calls.size shouldBe 1
        read(id).get("revision").asLong() shouldBe 2L
    }

    @Test
    fun `empty session update completes before the next delete`() {
        val closed = DEFINITION.replace("false", "true")
        val id = create(closed)
        request(path(id), "PATCH", updateBody(definition = closed.replace("오전 회차", "변경 회차"))).statusCode() shouldBe 204
        read(id).get("title").asString() shouldBe "변경 회차"
        read(id).get("revision").asLong() shouldBe 2L
        pending.size shouldBe 0
        request(path(id) + "?revision=2", "DELETE").statusCode() shouldBe 204
        pending.size shouldBe 0
        request(path(id)).statusCode() shouldBe 404
    }

    @Test
    fun `committed changes require remote completion and can recover after acknowledgement failure`() {
        val id = create()
        reconciliationHook = {
            read(id).get("revision").asLong() shouldBe 2L
            false
        }
        request(path(id), "PATCH", updateBody()).statusCode() shouldBe 409
        pending.size shouldBe 1
        reconciliationHook = { true }
        request(path(id), "PATCH", updateBody()).statusCode() shouldBe 204
        pending.size shouldBe 0
        calls.size shouldBe 1
        reconciliationHook = {
            request(internalPath(id), role = null, token = "test-expo-token").statusCode() shouldBe 404
            false
        }
        request(path(id) + "?revision=2", "DELETE").statusCode() shouldBe 409
        reconciliationHook = { true }
        request(path(id) + "/changes/retry", "POST").statusCode() shouldBe 204
        request(path(id) + "?revision=2", "DELETE").statusCode() shouldBe 204
        pending.size shouldBe 0
        calls.size shouldBe 2
    }

    private fun path(expoId: String = EXPO_ID): String = "/expo/$expoId/preregister-sessions"

    @Test
    fun `대기 정원 채택은 관리자만 기대값을 신청 서비스 내부 API로 전달하고 응답 상태를 변환한다`() {
        val id = create()
        val target = path(id) + "/waiting-capacity"
        request(target, "PUT", """{"expected":10}""", role = null).statusCode() shouldBe 401
        request(target, "PUT", """{"expected":10}""", role = "ROLE_USER").statusCode() shouldBe 403
        listOf("""{"expected":-1}""", "{}", """{"expected":2147483648}""").forEach {
            withClue(it) { request(target, "PUT", it).statusCode() shouldBe 400 }
        }
        request(path(id, OTHER_EXPO_ID) + "/waiting-capacity", "PUT", """{"expected":10}""").statusCode() shouldBe 404
        request(path(id + 1) + "/waiting-capacity", "PUT", """{"expected":10}""").statusCode() shouldBe 404
        calls.size shouldBe 0

        request(target, "PUT", """{"expected":10}""").statusCode() shouldBe 204
        calls.single().let {
            it.method shouldBe "PUT"
            it.path shouldBe "/internal/expos/$EXPO_ID/preregister-sessions/$id/waiting-capacity"
            it.token shouldBe "test-application-token"
            mapper.readTree(it.body) shouldBe mapper.readTree("""{"expected":10}""")
        }
        mapOf(404 to 404, 409 to 409, 400 to 502, 401 to 502, 500 to 502, 503 to 502).forEach { (remote, local) ->
            forcedStatus = remote
            request(target, "PUT", """{"expected":10}""").statusCode() shouldBe local
        }
        forcedStatus = null
        read(id).get("revision").asLong() shouldBe 1L
        journalCount(id) shouldBe 0L

        markExpoDeleting()
        calls.clear()
        request(target, "PUT", """{"expected":10}""").statusCode() shouldBe 409
        calls.size shouldBe 0
    }

    @Test
    fun `history records committed changes once and rolls back with the definition`() {
        val id = create()

        fun count() = jdbc.queryForObject("SELECT count(*) FROM tb_admin_change_history WHERE expo_id=?", Long::class.java, EXPO_ID)
        count() shouldBe 1L
        forcedStatus = 503
        request(path(id), "PATCH", updateBody()).statusCode() shouldBe 502
        count() shouldBe 1L
        forcedStatus = null
        request(path(id) + "/changes/retry", "POST", subject = "2").statusCode() shouldBe 204
        request(path(id) + "/changes/retry", "POST").statusCode() shouldBe 204
        count() shouldBe 2L
        jdbc.queryForList("SELECT admin_id FROM tb_admin_change_history", Long::class.java) shouldBe listOf(1L, 1L)
        jdbc.execute("ALTER TABLE tb_admin_change_history ADD CONSTRAINT test_reject_history CHECK (type <> 'SESSION_DELETE')")
        try {
            request(path(id) + "?revision=2", "DELETE").statusCode() shouldBe 500
            read(id).get("revision").asLong() shouldBe 2L
            count() shouldBe 2L
        } finally {
            jdbc.execute("ALTER TABLE tb_admin_change_history DROP CONSTRAINT test_reject_history")
        }
        request(path(id) + "/changes/retry", "POST").statusCode() shouldBe 204
        request(path(id) + "/changes/retry", "POST").statusCode() shouldBe 204
        count() shouldBe 3L
        jdbc.queryForList("SELECT type FROM tb_admin_change_history ORDER BY id", String::class.java) shouldBe
            listOf("SESSION_CREATE", "SESSION_UPDATE", "SESSION_DELETE")
    }

    private fun path(
        id: Long,
        expoId: String = EXPO_ID,
    ): String = "${path(expoId)}/$id"

    private fun internalPath(
        id: Long? = null,
        expoId: String = EXPO_ID,
    ): String = "/internal${path(expoId)}" + (id?.let { "/$it" } ?: "")

    private fun create(
        definition: String = DEFINITION,
        expoId: String = EXPO_ID,
    ): Long {
        val response = request(path(expoId), "POST", definition)
        response.statusCode() shouldBe 201
        return mapper.readTree(response.body()).get("id").asLong()
    }

    private fun setDefaults(
        expoId: String,
        teacher: Int?,
        nonTeacher: Int?,
    ) {
        val expo = expos.findById(expoId).orElseThrow()
        expos.saveAndFlush(
            Expo(
                expo.id,
                expo.title,
                expo.description,
                expo.startedDay,
                expo.finishedDay,
                expo.location,
                expo.coverImage,
                expo.x,
                expo.y,
                expo.applicationPerson,
                expo.yesterdayApplicationPerson,
                teacher,
                nonTeacher,
            ),
        )
    }

    private fun read(id: Long): JsonNode = mapper.readTree(request(path(id)).body())

    private fun withDeadline(deadline: String): String = DEFINITION.dropLast(1) + ",\"cancelEndedAt\":\"$deadline\"}"

    private fun journalCount(id: Long): Long = if (sessionChanges.existsById(id)) 1L else 0L

    private fun markExpoDeleting() {
        expos.saveAndFlush(expos.findById(EXPO_ID).orElseThrow().apply { markDeleting() })
    }

    private fun updateBody(
        revision: Long = 1,
        definition: String = DEFINITION.replace("false", "true"),
    ): String = """{"revision":$revision,"definition":$definition}"""

    private fun request(
        path: String,
        method: String = "GET",
        body: String = "",
        role: String? = "ROLE_ADMIN",
        token: String? = null,
        subject: String = "1",
    ): HttpResponse<String> {
        val request =
            HttpRequest
                .newBuilder(URI.create("http://localhost:$port$path"))
                .timeout(java.time.Duration.ofSeconds(15))
                .header("Content-Type", "application/json")
                .apply {
                    role?.let { header("Authorization", "Bearer ${TestJwt.token(it, subject = subject)}") }
                    token?.let { header("X-Internal-Token", it) }
                }.method(method, if (body.isEmpty()) HttpRequest.BodyPublishers.noBody() else HttpRequest.BodyPublishers.ofString(body))
                .build()
        return http.send(request, HttpResponse.BodyHandlers.ofString())
    }

    private data class ChangeCall(
        val method: String,
        val path: String,
        val token: String?,
        val body: String,
        val operation: String,
    )

    companion object {
        private const val EXPO_ID = "0190abcd-0000-7000-8000-000000000001"
        private const val OTHER_EXPO_ID = "0190abcd-0000-7000-8000-000000000002"
        private const val DEFINITION =
            """{"title":"오전 회차","startedAt":"2026-10-31T09:30:00+09:00","endedAt":"2026-10-31T12:30:00+09:00","place":"광주","capacity":100,"waitingCapacity":10,"closed":false}"""
        private val calls = CopyOnWriteArrayList<ChangeCall>()
        private val pending = ConcurrentHashMap<String, String>()
        private val histories = ConcurrentHashMap.newKeySet<Long>()

        @Volatile
        private var forcedStatus: Int? = null

        @Volatile
        private var responseHook: ((ChangeCall, Int) -> Unit)? = null

        @Volatile
        private var reconciliationHook: () -> Boolean = { true }

        @Volatile
        private var loseReconciliationResponse = false

        private val serverExecutor = Executors.newFixedThreadPool(4)

        private val server =
            HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
                createContext("/internal/") { exchange ->
                    if (exchange.requestURI.path.endsWith("/reconciliation")) {
                        exchange.requestMethod shouldBe "POST"
                        exchange.requestHeaders.getFirst("X-Internal-Token") shouldBe "test-application-token"
                        val reconciled = reconciliationHook()
                        if (reconciled) {
                            val prefix = exchange.requestURI.path.removeSuffix("reconciliation")
                            pending.keys.removeIf { it.startsWith(prefix) }
                        }
                        if (loseReconciliationResponse) {
                            exchange.close()
                            return@createContext
                        }
                        val response = reconciled.toString().toByteArray()
                        exchange.sendResponseHeaders(200, response.size.toLong())
                        exchange.responseBody.use { it.write(response) }
                        exchange.close()
                        return@createContext
                    }
                    val body = exchange.requestBody.readAllBytes().toString(Charsets.UTF_8)
                    val tree = ObjectMapper().readTree(body)
                    val path = exchange.requestURI.path
                    val id = path.substringAfter("/preregister-sessions/").substringBefore('/').toLong()
                    val call =
                        ChangeCall(
                            exchange.requestMethod,
                            path,
                            exchange.requestHeaders.getFirst("X-Internal-Token"),
                            body,
                            tree.get("operation")?.asString() ?: "ADOPT",
                        )
                    calls += call
                    val status =
                        forcedStatus ?: if (call.operation == "ADOPT") {
                            204
                        } else if (id in histories && tree.get("definitionChanged").asBoolean()) {
                            409
                        } else if (pending.keys.any {
                                it != path && it.substringBefore(
                                    "/changes/",
                                ) == path.substringBefore("/changes/")
                            }
                        ) {
                            409
                        } else {
                            val previous = pending.putIfAbsent(path, body)
                            if (previous == null || previous == body) 204 else 409
                        }
                    responseHook?.invoke(call, status)
                    exchange.sendResponseHeaders(status, -1)
                    exchange.close()
                }
                executor = serverExecutor
                start()
            }
        private val applicationUrl = "http://127.0.0.1:${server.address.port}"

        @JvmStatic
        @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("JWT_PUBLIC_KEY") { TestJwt.publicKeyPem }
            registry.add("expo.standard.application-service-url") { applicationUrl }
            registry.add("APPLICATION_INTERNAL_TOKEN") { "test-application-token" }
        }

        @JvmStatic
        @AfterAll
        fun stopServer() {
            server.stop(0)
            serverExecutor.shutdownNow()
        }

        @JvmStatic
        @Container
        @ServiceConnection
        val postgres = PostgreSQLContainer("postgres:17-alpine")
    }
}
