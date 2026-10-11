package team.startup.expo.domain.expo

import com.sun.net.httpserver.HttpServer
import io.kotest.matchers.shouldBe
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
import team.startup.expo.domain.expo.repository.ExpoRepository
import team.startup.expo.domain.expo.service.PreregisterSessionChangesClient
import team.startup.expo.support.TestJwt
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Instant
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
    private lateinit var transactions: TransactionTemplate

    @Autowired
    private lateinit var expos: ExpoRepository

    private val http = HttpClient.newHttpClient()

    @BeforeEach
    fun setUp() {
        calls.clear()
        pending.clear()
        histories.clear()
        forcedStatus = null
        responseHook = null
        ReflectionTestUtils.setField(changes, "applicationUrl", applicationUrl)
        ReflectionTestUtils.setField(changes, "internalToken", "test-application-token")
        jdbc.execute("TRUNCATE TABLE tb_expo RESTART IDENTITY CASCADE")
        listOf(EXPO_ID, OTHER_EXPO_ID).forEach { id ->
            jdbc.update(
                """INSERT INTO tb_expo (id,title,description,started_day,finished_day,location,x,y,application_person,yesterday_application_person)
                   VALUES (?,'박람회','설명','2026-10-31','2026-11-01','광주','127','37',5,3)""",
                id,
            )
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
        jdbc.queryForObject("SELECT application_person FROM tb_expo WHERE id = ?", Long::class.java, EXPO_ID) shouldBe 5L
        jdbc.queryForObject("SELECT yesterday_application_person FROM tb_expo WHERE id = ?", Long::class.java, EXPO_ID) shouldBe 3L
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
        val body = updateBody()
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
        pending.size shouldBe 1
        journalCount(id) shouldBe 1L
        request(path(id), "PATCH", updateBody(definition = DEFINITION.replace("100", "101"))).statusCode() shouldBe 409
        request(path(id) + "/changes/retry", "POST").statusCode() shouldBe 204
        read(id).get("revision").asLong() shouldBe 2L
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
        jdbc.update("DELETE FROM tb_expo WHERE id = ?", EXPO_ID)
        jdbc.queryForObject("SELECT COUNT(*) FROM tb_preregister_session", Long::class.java) shouldBe 1L
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

    private fun path(expoId: String = EXPO_ID): String = "/expo/$expoId/preregister-sessions"

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

    private fun read(id: Long): JsonNode = mapper.readTree(request(path(id)).body())

    private fun journalCount(id: Long): Long =
        jdbc.queryForObject("SELECT COUNT(*) FROM tb_preregister_session_change WHERE session_id=?", Long::class.java, id)!!

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
    ): HttpResponse<String> {
        val request =
            HttpRequest
                .newBuilder(URI.create("http://localhost:$port$path"))
                .timeout(java.time.Duration.ofSeconds(15))
                .header("Content-Type", "application/json")
                .apply {
                    role?.let { header("Authorization", "Bearer ${TestJwt.token(it)}") }
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

        private val serverExecutor = Executors.newFixedThreadPool(4)

        private val server =
            HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
                createContext("/internal/") { exchange ->
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
                            tree.get("operation").asString(),
                        )
                    calls += call
                    val status =
                        forcedStatus ?: if (id in histories && tree.get("definitionChanged").asBoolean()) {
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
            registry.add("expo.standard.internal-token") { "test-application-token" }
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
