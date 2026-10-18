package team.startup.expo.domain.expo

import com.sun.net.httpserver.HttpServer
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterAll
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
import org.springframework.test.util.ReflectionTestUtils
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import team.startup.expo.domain.expo.entity.AdminApplicationCancelAccess
import team.startup.expo.domain.expo.entity.AdminApplicationPromoteAccess
import team.startup.expo.domain.expo.entity.Expo
import team.startup.expo.domain.expo.entity.ProgramChange
import team.startup.expo.domain.expo.presentation.dto.request.ProgramSessionPageRequest
import team.startup.expo.domain.expo.repository.AdminApplicationCancelAccessRepository
import team.startup.expo.domain.expo.repository.AdminApplicationPromoteAccessRepository
import team.startup.expo.domain.expo.repository.ExpoRepository
import team.startup.expo.domain.expo.repository.ProgramChangeRepository
import team.startup.expo.domain.expo.repository.ProgramSessionQueryRepository
import team.startup.expo.domain.expo.service.AdminAuthorityClient
import team.startup.expo.domain.expo.service.GetProgramSessionPageService
import team.startup.expo.domain.training.repository.TrainingProgramRepository
import team.startup.expo.support.TestJwt
import tools.jackson.databind.ObjectMapper
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@SpringBootTest(webEnvironment = RANDOM_PORT, properties = ["eureka.client.enabled=false", "EXPO_INTERNAL_TOKEN=contract-service-token"])
@EntityScan("team.startup.expo.domain")
@Testcontainers
class AdminExpoAccessPostgresHttpTests {
    @LocalServerPort private var port = 0

    @Autowired private lateinit var jdbc: JdbcTemplate

    @Autowired private lateinit var mapper: ObjectMapper

    @Autowired private lateinit var authority: AdminAuthorityClient

    @Autowired private lateinit var transactions: PlatformTransactionManager

    @Autowired private lateinit var programs: ProgramSessionQueryRepository

    @Autowired private lateinit var pages: GetProgramSessionPageService

    @Autowired private lateinit var expos: ExpoRepository

    @Autowired private lateinit var cancelGrants: AdminApplicationCancelAccessRepository

    @Autowired private lateinit var promoteGrants: AdminApplicationPromoteAccessRepository

    @Autowired private lateinit var programChanges: ProgramChangeRepository

    @Autowired private lateinit var trainingPrograms: TrainingProgramRepository
    private val http = HttpClient.newHttpClient()

    @BeforeEach
    fun seed() {
        userStatus = 200
        userCalls.set(0)
        forwardedOriginalJwt = false
        jdbc.execute("TRUNCATE tb_expo RESTART IDENTITY CASCADE")
        listOf(EXPO, OTHER).forEach {
            expos.save(Expo(it, "expo", "description", "2026-10-12", "2026-10-13", "place", null, "1", "2", 0, 0))
        }
        listOf("standard", "training").forEach { type ->
            val categoryColumn = if (type == "training") ",category" else ""
            val categoryValue = if (type == "training") ",'ESSENTIAL'" else ""
            jdbc.update(
                "INSERT INTO tb_${type}_program(id,title,started_at,ended_at,expo_id,group_id,revision$categoryColumn) " +
                    "VALUES (1,'session','2026-10-13 09:00','2026-10-13 10:00',?,?,1$categoryValue)",
                EXPO,
                if (type == "training") TRAINING_GROUP else GROUP,
            )
            jdbc.update(
                "INSERT INTO tb_${type}_program(id,title,started_at,ended_at,expo_id,group_id,revision$categoryColumn) " +
                    "VALUES (2,'other','2026-10-13 09:00','2026-10-13 10:00',?,?,1$categoryValue)",
                OTHER,
                UUID.randomUUID(),
            )
        }
    }

    @Test
    fun `cancel requires an explicit event grant and observes committed revocation with the same JWT`() {
        val jwt = TestJwt.token("ROLE_ADMIN")
        val path = "/internal/expo/$EXPO/admin-application-cancel-access"
        request(path, jwt, token = null).statusCode() shouldBe 401
        request(path).statusCode() shouldBe 401
        request(path, "forged").statusCode() shouldBe 401
        request(path, TestJwt.token("ROLE_ADMIN", ttlSeconds = -1)).statusCode() shouldBe 401
        request(path, TestJwt.token("ROLE_STANDARD")).statusCode() shouldBe 403
        access(jwt = jwt).statusCode() shouldBe 200
        request("$path?permission=APPLICATION_CANCEL&adminId=999", jwt).statusCode() shouldBe 403
        cancelGrants.save(AdminApplicationCancelAccess(EXPO, 1))
        val response = request(path, jwt)
        response.statusCode() shouldBe 200
        val body = mapper.readTree(response.body())
        body.get("expoId").asString() shouldBe EXPO
        body.get("adminId").asLong() shouldBe 1L
        body.get("permission").asString() shouldBe "APPLICATION_CANCEL"
        body.get("policyVersion").asString() shouldBe "EXPO_ADMIN_APPLICATION_CANCEL_V1"
        body.get("scope").asString() shouldBe "EXPO_ADMIN"
        response.headers().firstValue("Cache-Control").orElse("") shouldBe "no-store"
        forwardedOriginalJwt shouldBe true
        request(path, TestJwt.token("ROLE_ADMIN", subject = "2")).statusCode() shouldBe 403
        request("/internal/expo/$OTHER/admin-application-cancel-access", jwt).statusCode() shouldBe 403
        request("/internal/expo/missing/admin-application-cancel-access", jwt).statusCode() shouldBe 404
        userStatus = 403
        request(path, jwt).statusCode() shouldBe 403
        userStatus = 401
        request(path, jwt).statusCode() shouldBe 401
        userStatus = 503
        request(path, jwt).statusCode() shouldBe 503
        userStatus = 200
        cancelGrants.deleteAll()
        request(path, jwt).statusCode() shouldBe 403
        access(jwt = jwt).statusCode() shouldBe 200
        cancelGrants.save(AdminApplicationCancelAccess(EXPO, 1))
        expos.save(expos.findById(EXPO).orElseThrow().apply { markDeleting() })
        request(path, jwt).statusCode() shouldBe 503
    }

    @Test
    fun `manual promotion requires its own event grant rather than cancel or read access`() {
        val jwt = TestJwt.token("ROLE_ADMIN")
        val path = "/internal/expo/$EXPO/admin-application-promote-access"
        request(path, jwt, token = null).statusCode() shouldBe 401
        request(path).statusCode() shouldBe 401
        request(path, TestJwt.token("ROLE_STANDARD")).statusCode() shouldBe 403
        cancelGrants.save(AdminApplicationCancelAccess(EXPO, 1))
        access(jwt = jwt).statusCode() shouldBe 200
        request(path, jwt).statusCode() shouldBe 403
        promoteGrants.save(AdminApplicationPromoteAccess(EXPO, 1))
        val response = request(path, jwt)
        response.statusCode() shouldBe 200
        val body = mapper.readTree(response.body())
        body.get("expoId").asString() shouldBe EXPO
        body.get("adminId").asLong() shouldBe 1L
        body.get("permission").asString() shouldBe "APPLICATION_PROMOTE"
        body.get("policyVersion").asString() shouldBe "EXPO_ADMIN_APPLICATION_PROMOTE_V1"
        body.get("scope").asString() shouldBe "EXPO_ADMIN"
        response.headers().firstValue("Cache-Control").orElse("") shouldBe "no-store"
        request(path, TestJwt.token("ROLE_ADMIN", subject = "2")).statusCode() shouldBe 403
        request("/internal/expo/$OTHER/admin-application-promote-access", jwt).statusCode() shouldBe 403
        request("/internal/expo/missing/admin-application-promote-access", jwt).statusCode() shouldBe 404
        userStatus = 403
        request(path, jwt).statusCode() shouldBe 403
        userStatus = 200
        promoteGrants.deleteAll()
        request(path, jwt).statusCode() shouldBe 403
        promoteGrants.save(AdminApplicationPromoteAccess(EXPO, 1))
        expos.save(expos.findById(EXPO).orElseThrow().apply { markDeleting() })
        request(path, jwt).statusCode() shouldBe 503
    }

    @Test
    fun `original JWT and service token are independently mandatory and spoofed identity is ignored`() {
        access(token = null).statusCode() shouldBe 401
        access(jwt = null).statusCode() shouldBe 401
        access(jwt = "forged").statusCode() shouldBe 401
        access(jwt = TestJwt.token("ROLE_ADMIN").substringBeforeLast('.') + ".AAAA").statusCode() shouldBe 401
        access(jwt = TestJwt.token("ROLE_ADMIN", subject = "0")).statusCode() shouldBe 401
        access(jwt = TestJwt.token("ROLE_ADMIN", subject = "invalid")).statusCode() shouldBe 401
        access(jwt = TestJwt.token("")).statusCode() shouldBe 401
        access(jwt = TestJwt.token("ROLE_ADMIN", ttlSeconds = 901)).statusCode() shouldBe 401
        access(jwt = TestJwt.token("ROLE_ADMIN", ttlSeconds = -1)).statusCode() shouldBe 401
        access(jwt = TestJwt.token("ROLE_STANDARD")).statusCode() shouldBe 403
        userCalls.get() shouldBe 0
        val response = access(query = "?adminId=999&role=ROLE_STANDARD")
        response.statusCode() shouldBe 200
        val body = mapper.readTree(response.body())
        body.get("adminId").asLong() shouldBe 1L
        body.get("permission").asString() shouldBe "APPLICATION_READ"
        body.get("policyVersion").asString() shouldBe "GLOBAL_ADMIN_APPLICATION_READ_V1"
        body.get("scope").asString() shouldBe "GLOBAL_ADMIN"
        forwardedOriginalJwt shouldBe true
        response.headers().firstValue("Cache-Control").orElse("") shouldBe "no-store"
        request("/expo/$EXPO", jwt = null).statusCode() shouldBe 401
    }

    @Test
    fun `live authority revocation deletion and failures take precedence over event reads`() {
        access().statusCode() shouldBe 200
        userStatus = 403
        access().statusCode() shouldBe 403
        access(expo = "missing").statusCode() shouldBe 403
        userStatus = 401
        access().statusCode() shouldBe 401
        userStatus = 503
        access().statusCode() shouldBe 503
        userStatus = 500
        access().statusCode() shouldBe 503
        userStatus = 302
        access().statusCode() shouldBe 503
        userStatus = 200
        access(expo = "missing").statusCode() shouldBe 404
        access(expo = OTHER).statusCode() shouldBe 200
        expos.save(expos.findById(EXPO).orElseThrow().apply { markDeleting() })
        access().statusCode() shouldBe 503
        request(supply).statusCode() shouldBe 503
    }

    @Test
    fun `unconfigured and disconnected authority never grants access`() {
        val url = "http://127.0.0.1:${user.address.port}"
        try {
            ReflectionTestUtils.setField(authority, "userServiceUrl", "")
            access().statusCode() shouldBe 503
            ReflectionTestUtils.setField(authority, "userServiceUrl", "http://127.0.0.1:1")
            access().statusCode() shouldBe 503
        } finally {
            ReflectionTestUtils.setField(authority, "userServiceUrl", url)
        }
    }

    @Test
    fun `event scoped definitions page both types with colliding IDs and reject foreign selections`() {
        request(supply, token = null).statusCode() shouldBe 401
        val first = request("$supply?size=1")
        first.statusCode() shouldBe 200
        val page = mapper.readTree(first.body())
        page.get("totalElements").asLong() shouldBe 2L
        page.get("items")[0].get("programType").asString() shouldBe "STANDARD"
        page.get("items")[0].get("id").asLong() shouldBe 1L
        val version = page.get("definitionVersion").asString()
        val second = mapper.readTree(request("$supply?size=1&page=1&definitionVersion=$version").body())
        second.get("items")[0].get("programType").asString() shouldBe "TRAINING"
        second.get("items")[0].get("id").asLong() shouldBe 1L
        second.get("items")[0].get("groupId").asString() shouldBe TRAINING_GROUP.toString()
        second.get("items")[0].get("revision").asLong() shouldBe 1L
        request("$supply?programType=TRAINING&programId=2").statusCode() shouldBe 404
        request("$supply?groupId=${UUID.randomUUID()}").statusCode() shouldBe 404
        request("$supply?programId=1").statusCode() shouldBe 400
        request("$supply?page=1").statusCode() shouldBe 400
        request("$supply?size=101").statusCode() shouldBe 400
        request("$supply?size=0").statusCode() shouldBe 400
        request("$supply?page=-1").statusCode() shouldBe 400
        request("$supply?role=ROLE_ADMIN").statusCode() shouldBe 400
        request("$supply?size=1&size=2").statusCode() shouldBe 400
        request("$supply?groupId=").statusCode() shouldBe 400
        request("/internal/expo/missing/program-sessions").statusCode() shouldBe 404
    }

    @Test
    fun `definition changes invalidate later pages and deleted sessions cannot be selected`() {
        val version = mapper.readTree(request(supply).body()).get("definitionVersion").asString()
        jdbc.update("UPDATE tb_training_program SET title='changed', revision=2 WHERE expo_id=?", EXPO)
        request("$supply?page=1&size=1&definitionVersion=$version").statusCode() shouldBe 409
        val current = mapper.readTree(request("$supply?programType=TRAINING&programId=1").body())
        current.get("items")[0].get("revision").asLong() shouldBe 2L
        trainingPrograms.deleteAll(trainingPrograms.findByExpoIdOrderByIdAsc(EXPO))
        request("$supply?programType=TRAINING&programId=1").statusCode() shouldBe 404
        mapper.readTree(request(supply).body()).get("totalElements").asLong() shouldBe 1L
        expos.deleteById(EXPO)
        access().statusCode() shouldBe 404
    }

    @Test
    fun `pending change and definition database outage are unavailable rather than empty`() {
        programChanges.save(ProgramChange(EXPO, UUID.randomUUID(), "[]", "{}"))
        request(supply).statusCode() shouldBe 503
        programChanges.deleteById(EXPO)
        jdbc.execute("ALTER TABLE tb_standard_program RENAME TO test_unavailable_program")
        try {
            request(supply).statusCode() shouldBe 503
        } finally {
            jdbc.execute("ALTER TABLE test_unavailable_program RENAME TO tb_standard_program")
        }
        jdbc.execute("ALTER TABLE tb_expo RENAME TO test_unavailable_expo")
        try {
            access().statusCode() shouldBe 503
        } finally {
            jdbc.execute("ALTER TABLE test_unavailable_expo RENAME TO tb_expo")
        }
    }

    @Test
    fun `page limit is applied by database and enrollment policy version is preserved`() {
        jdbc.update(
            "INSERT INTO tb_standard_program(id,title,started_at,ended_at,expo_id,group_id,revision) " +
                "SELECT n,'session','2026-10-13 09:00','2026-10-13 10:00',?,gen_random_uuid(),1 FROM generate_series(3,102) n",
            EXPO,
        )
        val policy =
            """
            {"groupId":"$TRAINING_GROUP","formId":"${UUID.randomUUID()}","allowedOccupations":["TEACHER"],"capacity":10,"preRegistrationCapacity":7,"onsiteCapacity":3,"waitingCapacity":2,
             "applicationStartedAt":"2026-10-11T00:00:00Z","applicationEndedAt":"2026-10-12T00:00:00Z",
             "cancelEndedAt":"2026-10-12T00:00:00Z","promotionEndedAt":"2026-10-12T00:00:00Z","closed":true}
            """.trimIndent()
        jdbc.update("UPDATE tb_training_program SET policy_json=?,revision=3 WHERE id=1", policy)
        val page = mapper.readTree(request(supply).body())
        page.get("items").size() shouldBe 100
        page.get("totalElements").asLong() shouldBe 102L
        val last = mapper.readTree(request("$supply?page=1&definitionVersion=${page.get("definitionVersion").asString()}").body())
        last.get("items").size() shouldBe 2
        val training = last.get("items")[1]
        training.get("revision").asLong() shouldBe 3L
        training.get("policy").get("capacity").asInt() shouldBe 10
        training.get("policy").get("preRegistrationCapacity").asInt() shouldBe 7
        training.get("policy").get("onsiteCapacity").asInt() shouldBe 3
        training.get("policy").get("waitingCapacity").asInt() shouldBe 2
        training.get("policy").get("closed").asBoolean() shouldBe true
    }

    private val supply get() = "/internal/expo/$EXPO/program-sessions"

    @Test
    fun `history requires original current authority and isolates events with stable filtered pages`() {
        val path = "/expo/$EXPO/admin-change-history"
        request(path, token = null).statusCode() shouldBe 401
        request(path, jwt = "forged", token = null).statusCode() shouldBe 401
        request(path, jwt = TestJwt.token("ROLE_USER"), token = null).statusCode() shouldBe 403

        fun history(query: String = "") = request(path + query, jwt = TestJwt.token("ROLE_ADMIN"), token = null)
        history().statusCode() shouldBe 200
        mapper.readTree(history().body()).get("totalElements").asLong() shouldBe 0L
        listOf(EXPO, EXPO, OTHER).forEachIndexed { index, expo ->
            jdbc.update(
                "INSERT INTO tb_admin_change_history(expo_id,type,target_type,target_id,summary,admin_id,occurred_at) " +
                    "VALUES (?,'SESSION_UPDATE','PREREGISTER_SESSION',?,'closed',?,'2026-10-12T01:00:00Z')",
                expo,
                index + 1,
                if (index == 0) null else 1L,
            )
        }
        val first = history("?size=1")
        first.statusCode() shouldBe 200
        first.headers().firstValue("Cache-Control").orElse("") shouldBe "no-store"
        val page = mapper.readTree(first.body())
        page.get("totalElements").asLong() shouldBe 2L
        page.get("items")[0].get("targetId").asLong() shouldBe 2L
        page.get("items")[0].get("adminId").asLong() shouldBe 1L
        val second = mapper.readTree(history("?size=1&page=1").body())
        second.get("items")[0].get("targetId").asLong() shouldBe 1L
        second.get("items")[0].get("adminId").isNull shouldBe true
        mapper.readTree(history("?type=PROGRAM_DELETE").body()).get("totalElements").asLong() shouldBe 0L
        mapper
            .readTree(history("?type=SESSION_UPDATE&from=2026-10-12T01:00:00Z&to=2026-10-12T02:00:00Z").body())
            .get("totalElements")
            .asLong() shouldBe 2L
        mapper.readTree(history("?to=2026-10-12T01:00:00Z").body()).get("totalElements").asLong() shouldBe 0L
        listOf(
            "?page=-1",
            "?size=0",
            "?size=101",
            "?type=invalid",
            "?from=invalid",
            "?from=2026-10-12T01:00:00Z&to=2026-10-12T01:00:00Z",
            "?from=2026-10-12T02:00:00Z&to=2026-10-12T01:00:00Z",
        ).forEach {
            history(it).statusCode() shouldBe 400
        }
        userStatus = 403
        history().statusCode() shouldBe 403
        userStatus = 503
        history().statusCode() shouldBe 503
        userStatus = 200
        request("/expo/missing/admin-change-history", jwt = TestJwt.token("ROLE_ADMIN"), token = null).statusCode() shouldBe 404
        forwardedOriginalJwt shouldBe true
    }

    @Test
    fun `history applies database page limits and excludes other events from counts`() {
        jdbc.update(
            "INSERT INTO tb_admin_change_history(expo_id,type,target_type,target_id,summary) " +
                "SELECT ?,'PROGRAM_CREATE','STANDARD',n,'session' FROM generate_series(1,105) n",
            EXPO,
        )
        jdbc.update(
            "INSERT INTO tb_admin_change_history(expo_id,type,target_type,target_id,summary) VALUES (?,'PROGRAM_CREATE','STANDARD',1,'foreign')",
            OTHER,
        )

        fun page(query: String) =
            mapper.readTree(request("/expo/$EXPO/admin-change-history$query", jwt = TestJwt.token("ROLE_ADMIN"), token = null).body())
        val first = page("?size=100")
        first.get("items").size() shouldBe 100
        first.get("totalElements").asLong() shouldBe 105L
        page("?size=100&page=1").get("items").size() shouldBe 5
        page("?size=100&page=2").get("items").size() shouldBe 0
        expos.save(expos.findById(EXPO).orElseThrow().apply { markDeleting() })
        request("/expo/$EXPO/admin-change-history", jwt = TestJwt.token("ROLE_ADMIN"), token = null).statusCode() shouldBe 503
    }

    @Test
    fun `count items and version use one Postgres snapshot despite concurrent committed change`() {
        val transaction =
            TransactionTemplate(transactions).apply {
                isolationLevel = TransactionDefinition.ISOLATION_REPEATABLE_READ
                isReadOnly = true
            }
        transaction.executeWithoutResult {
            val version = programs.version(EXPO)
            CompletableFuture
                .runAsync {
                    jdbc.update("UPDATE tb_standard_program SET title='concurrent',revision=2 WHERE expo_id=?", EXPO)
                }.get(5, TimeUnit.SECONDS)
            val result = pages.execute(EXPO, ProgramSessionPageRequest())
            result.definitionVersion shouldBe version
            result.totalElements shouldBe 2L
            result.items.first().title shouldBe "session"
            result.items.first().revision shouldBe 1L
        }
        mapper
            .readTree(request(supply).body())
            .get("items")[0]
            .get("revision")
            .asLong() shouldBe 2L
    }

    private fun access(
        expo: String = EXPO,
        jwt: String? = TestJwt.token("ROLE_ADMIN"),
        token: String? = "contract-service-token",
        query: String = "",
    ) = request("/internal/expo/$expo/admin-access$query", jwt, token)

    private fun request(
        path: String,
        jwt: String? = null,
        token: String? = "contract-service-token",
    ): HttpResponse<String> {
        val builder = HttpRequest.newBuilder(URI.create("http://localhost:$port$path")).GET()
        jwt?.let { builder.header("Authorization", "Bearer $it") }
        token?.let { builder.header("X-Internal-Token", it) }
        builder.header("X-User-Id", "999").header("X-User-Role", "ROLE_ADMIN")
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString())
    }

    companion object {
        private const val EXPO = "0190abcd-0000-7000-8000-000000000001"
        private const val OTHER = "0190abcd-0000-7000-8000-000000000002"
        private val GROUP = UUID.fromString("0190abcd-0000-7000-8000-000000000003")
        private val TRAINING_GROUP = UUID.fromString("0190abcd-0000-7000-8000-000000000004")
        private val userCalls = AtomicInteger()

        @Volatile private var userStatus = 200

        @Volatile private var forwardedOriginalJwt = false
        private val user =
            HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
                createContext("/admin/my") { exchange ->
                    userCalls.incrementAndGet()
                    forwardedOriginalJwt =
                        exchange.requestHeaders.getFirst("Authorization")?.startsWith("Bearer ey") == true &&
                        exchange.requestHeaders.getFirst("X-Internal-Token") == null &&
                        exchange.requestHeaders.getFirst("X-User-Id") == null
                    exchange.sendResponseHeaders(userStatus, -1)
                    exchange.close()
                }
                start()
            }

        @JvmStatic @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("JWT_PUBLIC_KEY") { TestJwt.publicKeyPem }
            registry.add("expo.standard.user-service-url") { "http://127.0.0.1:${user.address.port}" }
        }

        @JvmStatic @AfterAll
        fun stop() = user.stop(0)

        @JvmStatic @Container @ServiceConnection
        val postgres = PostgreSQLContainer("postgres:17-alpine")
    }
}
