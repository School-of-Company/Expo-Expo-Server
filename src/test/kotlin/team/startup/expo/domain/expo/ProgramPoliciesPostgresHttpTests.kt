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
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import team.startup.expo.support.TestJwt
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@SpringBootTest(
    webEnvironment = RANDOM_PORT,
    properties = [
        "eureka.client.enabled=false",
        "spring.jpa.hibernate.ddl-auto=validate",
        "EXPO_INTERNAL_TOKEN=expo-test-token",
    ],
)
@EntityScan("team.startup.expo.domain")
@Testcontainers
class ProgramPoliciesPostgresHttpTests {
    @LocalServerPort
    private var port = 0

    @Autowired
    private lateinit var jdbc: JdbcTemplate

    @Autowired
    private lateinit var mapper: ObjectMapper

    @Autowired
    private lateinit var transactions: TransactionTemplate

    @Autowired
    private lateinit var imageRepository: team.startup.expo.domain.image.repository.ExpoImageRepository

    private val http = HttpClient.newHttpClient()

    @BeforeEach
    fun setUp() {
        supplierJdbc = jdbc
        supplierTransactions = transactions
        supplierMapper = mapper
        lostResponse = false
        beforeReply = null
        wrongFormExpo = false
        malformedForm = false
        shortFormPeriod = false
        calls.clear()
        jdbc.execute("TRUNCATE tb_expo_image, tb_expo RESTART IDENTITY CASCADE")
        jdbc.execute(
            "CREATE TABLE IF NOT EXISTS test_admission (program_type TEXT NOT NULL, program_id BIGINT NOT NULL, confirmed INT NOT NULL DEFAULT 0, waiting INT NOT NULL DEFAULT 0, capacity INT NOT NULL, next_revision BIGINT NOT NULL DEFAULT 1, blocked BOOLEAN NOT NULL DEFAULT false, history BOOLEAN NOT NULL DEFAULT false, PRIMARY KEY(program_type,program_id))",
        )
        jdbc.execute("CREATE TABLE IF NOT EXISTS test_change_receipt (id TEXT PRIMARY KEY, body TEXT NOT NULL)")
        jdbc.execute("TRUNCATE test_admission, test_change_receipt")
        listOf(EXPO, OTHER).forEach { id ->
            jdbc.update(
                "INSERT INTO tb_expo (id,title,description,started_day,finished_day,location,x,y,application_person,yesterday_application_person) VALUES (?,'expo','description','2026-10-11','2026-10-12','place','127','37',0,0)",
                id,
            )
        }
        jdbc.update("UPDATE tb_expo SET cover_image=''")
    }

    @Test
    fun `existing ids remain sessions and same title form and numeric ids never infer a group`() {
        create("standard")
        create("standard")
        create("training")
        val standard = list("standard")
        val training = list("training")
        standard[0].get("id").asLong() shouldBe 1L
        training[0].get("id").asLong() shouldBe 1L
        (standard[0].get("groupId").asString() == standard[1].get("groupId").asString()) shouldBe false
        (standard[0].get("groupId").asString() == training[0].get("groupId").asString()) shouldBe false
        training[0].get("category").asString() shouldBe "ESSENTIAL"
        val linked = body("standard", policy = policy(group = standard[0].get("groupId").asString()))
        request("/standard/$EXPO", "POST", linked).statusCode() shouldBe 201
        list("standard")[2].get("groupId").asString() shouldBe standard[0].get("groupId").asString()
        val internal = request("/internal/expo/$EXPO/standard-programs/1", token = "expo-test-token", role = null)
        internal.statusCode() shouldBe 200
        mapper
            .readTree(internal.body())
            .get("policy")
            .get("capacity")
            .asInt() shouldBe 2
        val batch =
            request(
                "/internal/expo/$EXPO/training-programs/batch",
                "POST",
                """{"programIds":[1]}""",
                role = null,
                token = "expo-test-token",
            )
        batch.statusCode() shouldBe 200
        mapper.readTree(batch.body())[0].get("programType").asString() shouldBe "TRAINING"
        request("/internal/expo/$OTHER/standard-programs/1", role = null, token = "expo-test-token").statusCode() shouldBe 404
    }

    @Test
    fun `all create paths validate capacity occupation time form and group scope without partial rows`() {
        create("standard")
        request("/training/$OTHER", "POST", body("training", policy = null)).statusCode() shouldBe 201
        val otherGroup = list("training", OTHER)[0].get("groupId").asString()
        val invalid =
            listOf(
                policy(capacity = 0),
                policy(waiting = -1),
                policy(occupation = "UNKNOWN"),
                policy(occupation = ""),
                policy(group = otherGroup),
                policy(group = UUID.randomUUID().toString()),
                policy().replace("2026-10-12T00:00:00Z", "2026-10-10T00:00:00Z"),
            )
        invalid.forEach { p ->
            val response = request("/standard/list/$EXPO", "POST", "[${body("standard")},${body("standard", policy = p)}]")
            (response.statusCode() in listOf(400, 404)) shouldBe true
            list("standard").size() shouldBe 1
        }
        val trainingGroup = list("training", OTHER)[0].get("groupId").asString()
        request("/standard/$EXPO", "POST", body("standard", policy = policy(group = trainingGroup))).statusCode() shouldBe 400
        wrongFormExpo = true
        request("/standard/$EXPO", "POST", body("standard")).statusCode() shouldBe 400
        wrongFormExpo = false
        request("/standard/$EXPO", "POST", body("standard", policy = policy(form = TRAINING_FORM))).statusCode() shouldBe 400
        request("/standard/$EXPO", "POST", body("standard", policy = policy(form = UUID.randomUUID().toString()))).statusCode() shouldBe 404
        malformedForm = true
        request("/training/list/$EXPO", "POST", "[${body("training")}]").statusCode() shouldBe 502
        malformedForm = false
        shortFormPeriod = true
        request("/standard/$EXPO", "POST", body("standard")).statusCode() shouldBe 400
        shortFormPeriod = false
        request("/standard/$EXPO", "POST", body("standard").replace("2026-10-13 10:00", "2026-10-13 08:00")).statusCode() shouldBe 400
        jdbc.update("UPDATE tb_expo SET deleting_at=CURRENT_TIMESTAMP WHERE id=?", EXPO)
        request("/training/$EXPO", "POST", body("training")).statusCode() shouldBe 409
        request("/standard/list/$EXPO", "POST", "[${body("standard")}]").statusCode() shouldBe 409
    }

    @Test
    fun `active counts guard shrinking and history guards moving a group or deleting`() {
        create("standard")
        create("standard")
        seed("STANDARD", 1, confirmed = 2, waiting = 1)
        update(1, policy(capacity = 1)).statusCode() shouldBe 409
        readPolicy(1).get("capacity").asInt() shouldBe 2
        update(1, policy(waiting = 0)).statusCode() shouldBe 409
        update(1, policy(group = list("standard")[1].get("groupId").asString())).statusCode() shouldBe 409
        request("/standard/1", "DELETE").statusCode() shouldBe 409
        pending() shouldBe 0L
        update(1, policy(capacity = 3)).statusCode() shouldBe 204
        list("standard")[0].get("revision").asLong() shouldBe 2L
        supplierCount("STANDARD", 1) shouldBe 2
        jdbc.update("UPDATE test_admission SET confirmed=1,waiting=0 WHERE program_type='STANDARD' AND program_id=1")
        update(1, policy(capacity = 1, waiting = 0), revision = 2).statusCode() shouldBe 204
        readPolicy(1).get("waitingCapacity").asInt() shouldBe 0
        list("standard")[0].get("id").asLong() shouldBe 1L
        update(1, policy(capacity = 1, waiting = 0), revision = 2).statusCode() shouldBe 204
        update(1, policy(capacity = 4), revision = 1).statusCode() shouldBe 409
        request("/standard/1", "PATCH", body("standard", id = 1)).statusCode() shouldBe 400
    }

    @Test
    fun `lost approval response persists pending blocks admission and replays the exact operation`() {
        create("standard")
        seed("STANDARD", 1)
        lostResponse = true
        update(1, policy(capacity = 3)).statusCode() shouldBe 502
        pending() shouldBe 1L
        list("standard")[0].get("pendingRevision").asLong() shouldBe 2L
        list("standard")[0].get("revision").asLong() shouldBe 1L
        request("/internal/expo/$EXPO/standard-programs/1", role = null, token = "expo-test-token").statusCode() shouldBe 409
        admit("STANDARD", 1) shouldBe false
        request(
            "/standard/application/$EXPO",
            "POST",
            """{"phoneNumber":"01012345678","standardProIds":[1]}""",
            role = null,
        ).statusCode() shouldBe
            409
        request("/standard/$EXPO", "POST", body("standard")).statusCode() shouldBe 409
        request("/expo/$EXPO", "DELETE").statusCode() shouldBe 409
        request("/expo/$EXPO/program-changes/retry", "POST", role = null).statusCode() shouldBe 401
        request("/expo/$EXPO/program-changes/retry", "POST", role = "ROLE_USER").statusCode() shouldBe 403
        update(1, policy(capacity = 4)).statusCode() shouldBe 409
        lostResponse = false
        request("/expo/$EXPO/program-changes/retry", "POST").statusCode() shouldBe 204
        pending() shouldBe 0L
        readPolicy(1).get("capacity").asInt() shouldBe 3
        admit("STANDARD", 1) shouldBe true
        val prepares = calls.filter { it.first.contains("/program-changes/") }
        prepares.size shouldBe 2
        prepares[0] shouldBe prepares[1]
        request("/expo/$EXPO/program-changes/retry", "POST").statusCode() shouldBe 204
    }

    @Test
    fun `approval followed by a DB failure recovers without incrementing revision twice`() {
        create("training")
        seed("TRAINING", 1)
        try {
            jdbc.execute(
                """CREATE FUNCTION fail_program_update() RETURNS trigger AS ${'$'}t${'$'} BEGIN RAISE EXCEPTION 'forced'; END; ${'$'}t${'$'} LANGUAGE plpgsql""",
            )
            jdbc.execute(
                "CREATE TRIGGER fail_program_update BEFORE UPDATE ON tb_training_program FOR EACH ROW EXECUTE FUNCTION fail_program_update()",
            )
            request(
                "/training/1",
                "PATCH",
                body("training", policy = policy(form = TRAINING_FORM, capacity = 3), id = 1, revision = 1),
            ).statusCode() shouldBe
                500
        } finally {
            jdbc.execute("DROP TRIGGER IF EXISTS fail_program_update ON tb_training_program")
            jdbc.execute("DROP FUNCTION IF EXISTS fail_program_update()")
        }
        pending() shouldBe 1L
        list("training")[0].get("revision").asLong() shouldBe 1L
        request("/expo/$EXPO/program-changes/retry", "POST").statusCode() shouldBe 204
        list("training")[0].get("revision").asLong() shouldBe 2L
        pending() shouldBe 0L
    }

    @Test
    fun `concurrent admissions and shrinking share a supplier lock and pending blocks another admission`() {
        create("standard")
        seed("STANDARD", 1, confirmed = 1)
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val admission =
                CompletableFuture.supplyAsync({
                    start.await()
                    admit("STANDARD", 1)
                }, executor)
            val shrink =
                CompletableFuture.supplyAsync({
                    start.await()
                    update(1, policy(capacity = 1)).statusCode()
                }, executor)
            start.countDown()
            val admitted = admission.get(15, TimeUnit.SECONDS)
            val status = shrink.get(15, TimeUnit.SECONDS)
            if (admitted) {
                status shouldBe 409
                readPolicy(1).get("capacity").asInt() shouldBe 2
            } else {
                status shouldBe 204
                readPolicy(1).get("capacity").asInt() shouldBe 1
            }
            (supplierCount("STANDARD", 1) <= readPolicy(1).get("capacity").asInt()) shouldBe true
        } finally {
            executor.shutdownNow()
        }
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        beforeReply = {
            entered.countDown()
            check(release.await(10, TimeUnit.SECONDS))
        }
        val updateExecutor = Executors.newSingleThreadExecutor()
        try {
            val currentRevision = list("standard")[0].get("revision").asLong()
            val change = CompletableFuture.supplyAsync({ update(1, policy(capacity = 4), currentRevision).statusCode() }, updateExecutor)
            check(entered.await(10, TimeUnit.SECONDS))
            pending() shouldBe 1L
            admit("STANDARD", 1) shouldBe false
            release.countDown()
            change.get(15, TimeUnit.SECONDS) shouldBe 204
        } finally {
            release.countDown()
            updateExecutor.shutdownNow()
        }
    }

    @Test
    fun `nested expo PATCH keeps every policy and makes mixed program changes atomic`() {
        create("standard")
        create("training")
        seed("STANDARD", 1)
        seed("TRAINING", 1, confirmed = 2)
        val standard = body("standard", policy = policy(capacity = 3), id = 1, revision = 1)
        val training = body("training", policy = policy(form = TRAINING_FORM, capacity = 1), id = 1, revision = 1)
        request("/expo/$EXPO", "PATCH", expoBody(standard, training)).statusCode() shouldBe 409
        list("standard")[0].get("revision").asLong() shouldBe 1L
        list("training")[0].get("revision").asLong() shouldBe 1L
        jdbc.queryForObject("SELECT count(*) FROM test_admission WHERE blocked", Long::class.java) shouldBe 0L
        val acceptedTraining = body("training", policy = policy(form = TRAINING_FORM, capacity = 3), id = 1, revision = 1)
        lostResponse = true
        request("/expo/$EXPO", "PATCH", expoBody(standard, acceptedTraining)).statusCode() shouldBe 502
        list("standard")[0].get("revision").asLong() shouldBe 1L
        list("training")[0].get("revision").asLong() shouldBe 1L
        lostResponse = false
        request("/expo/$EXPO/program-changes/retry", "POST").statusCode() shouldBe 204
        list("standard")[0].get("revision").asLong() shouldBe 2L
        list("training")[0].get("revision").asLong() shouldBe 2L
        val legacyStandard = body("standard", policy = null, id = 1, revision = 2)
        val legacyTraining = body("training", policy = null, id = 1, revision = 2)
        request("/expo/$EXPO", "PATCH", expoBody(legacyStandard, legacyTraining)).statusCode() shouldBe 204
        list("standard")[0].get("policy").get("capacity").asInt() shouldBe 3
        request("/expo/$EXPO", "PATCH", expoBody(legacyStandard, null)).statusCode() shouldBe 409
        list("training").size() shouldBe 1
    }

    @Test
    fun `legacy admission is blocked for managed programs and DB checks reject invalid stored policy`() {
        create("standard")
        create("training")
        request(
            "/standard/application/$EXPO",
            "POST",
            """{"phoneNumber":"01012345678","standardProIds":[1]}""",
            role = null,
        ).statusCode() shouldBe
            409
        request("/training/application/1", "POST", """{"trainingId":"teacher"}""", role = null).statusCode() shouldBe 409
        request(
            "/training/application/list",
            "POST",
            """{"trainingId":"teacher","trainingProIds":[1]}""",
            role = null,
        ).statusCode() shouldBe
            409
        val original = jdbc.queryForObject("SELECT policy_json FROM tb_standard_program WHERE id=1", String::class.java)!!
        listOf(
            original.replace("\"capacity\":2", "\"capacity\":0"),
            original.replace("\"waitingCapacity\":1", "\"waitingCapacity\":-1"),
            original.replace("TEACHER", "UNKNOWN"),
        ).forEach { invalid ->
            assertThrows(DataIntegrityViolationException::class.java) {
                jdbc.update("UPDATE tb_standard_program SET policy_json=? WHERE id=1", invalid)
            }
        }
        jdbc.update("UPDATE tb_expo SET deleting_at=CURRENT_TIMESTAMP WHERE id=?", EXPO)
        update(1, policy(capacity = 3)).statusCode() shouldBe 409
        request("/standard/1", "DELETE").statusCode() shouldBe 409
        request("/internal/expo/$EXPO/standard-programs/1", role = null, token = "expo-test-token").statusCode() shouldBe 409
    }

    @Test
    fun `policy-free legacy requests retain unlimited standard and training category semantics`() {
        request("/standard/$EXPO", "POST", body("standard", policy = null)).statusCode() shouldBe 201
        request("/training/list/$EXPO", "POST", "[${body("training", policy = null)}]").statusCode() shouldBe 201
        list("standard")[0].get("policy").isNull shouldBe true
        list("training")[0].get("policy").isNull shouldBe true
        list("training")[0].get("category").asString() shouldBe "ESSENTIAL"
        request("/standard/1", "PATCH", body("standard", policy = null, id = 1).replace("session", "renamed")).statusCode() shouldBe 204
        list("standard")[0].get("id").asLong() shouldBe 1L
        request("/standard/1", "DELETE").statusCode() shouldBe 204
        request("/training/1", "DELETE").statusCode() shouldBe 204
        calls.any { it.first == "/internal/standard-program-applications/program/1" } shouldBe true
        calls.any { it.first == "/internal/training-program-applications/program/1" } shouldBe true
        calls.any { it.first.contains("/program-changes/") } shouldBe false
    }

    @Test
    fun `nested PATCH creates validate policies and preserve every id on failure`() {
        request("/expo/$EXPO", "PATCH", expoBody(body("standard"), body("training"))).statusCode() shouldBe 204
        list("standard")[0].get("policy").get("capacity").asInt() shouldBe 2
        list("training")[0].get("policy").get("capacity").asInt() shouldBe 2
        request(
            "/expo/$EXPO",
            "PATCH",
            expoBody(
                body("standard", id = 1, revision = 1),
                body("training", policy = policy(form = TRAINING_FORM, capacity = 0)),
            ),
        ).statusCode() shouldBe 400
        list("standard")[0].get("id").asLong() shouldBe 1L
        list("training")[0].get("id").asLong() shouldBe 1L
        pending() shouldBe 0L
        request("/expo/missing", "PATCH", expoBody(null, null)).statusCode() shouldBe 404
    }

    @Test
    fun `nested Expo create rejects foreign forms and legacy create retains the nested API`() {
        jdbc.update(
            "INSERT INTO tb_expo_image(id,storage_provider,object_key,public_url,content_type,uploaded_by,created_at,status) VALUES ('image','local','test','/image/test','image/png','1',CURRENT_TIMESTAMP,'PENDING')",
        )
        val createBody =
            expoBody(body("standard"), null)
                .replace("updateStandardProRequestDto", "addStandardProRequestDto")
                .replace("updateTrainingProRequestDto", "addTrainingProRequestDto")
                .replace("\"coverImage\":\"\"", "\"coverImage\":\"/image/test\"")
        request("/expo", "POST", createBody).statusCode() shouldBe 400
        jdbc.queryForObject("SELECT count(*) FROM tb_expo", Long::class.java) shouldBe 2L
        jdbc.queryForObject("SELECT status FROM tb_expo_image WHERE id='image'", String::class.java) shouldBe "PENDING"
        val legacy =
            createBody.replace(
                "\"addStandardProRequestDto\":[${body("standard")}]",
                "\"addStandardProRequestDto\":[${body("standard", policy = null)}]",
            )
        val response = request("/expo", "POST", legacy)
        response.statusCode() shouldBe 201
        val id = mapper.readTree(response.body()).get("expoId").asString()
        list("standard", id).size() shouldBe 1
        list("standard", id)[0].get("policy").isNull shouldBe true
        list("standard", id)[0].get("groupId").isString shouldBe true
    }

    @Test
    fun `unused managed sessions delete through approval and existing attendance cleanup`() {
        create("standard")
        create("training")
        seed("STANDARD", 1)
        seed("TRAINING", 1)
        request("/standard/1", "DELETE").statusCode() shouldBe 204
        request("/training/1", "DELETE").statusCode() shouldBe 204
        list("standard").size() shouldBe 0
        list("training").size() shouldBe 0
        calls.any { it.first == "/internal/program-attendances/standard/1" } shouldBe true
        calls.any { it.first == "/internal/program-attendances/training/1" } shouldBe true
        val changes = calls.filter { it.first.contains("/program-changes/") }
        supplierMapper
            .readTree(changes[0].second)
            .get("mutations")[0]
            .get("previous")
            .get("programType")
            .asString() shouldBe "STANDARD"
        supplierMapper
            .readTree(changes[1].second)
            .get("mutations")[0]
            .get("previous")
            .get("programType")
            .asString() shouldBe "TRAINING"
    }

    @Test
    fun `same revision concurrent edits accept one and cannot overwrite the pending operation`() {
        create("standard")
        seed("STANDARD", 1)
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val changes =
                listOf(3, 4).map { capacity ->
                    CompletableFuture.supplyAsync({
                        start.await()
                        update(1, policy(capacity = capacity)).statusCode()
                    }, executor)
                }
            start.countDown()
            changes.map { it.get(15, TimeUnit.SECONDS) }.sorted() shouldBe listOf(204, 409)
            list("standard")[0].get("revision").asLong() shouldBe 2L
            calls.count { it.first.contains("/program-changes/") } shouldBe 1
        } finally {
            executor.shutdownNow()
        }
    }

    private fun create(
        type: String,
        expo: String = EXPO,
    ) {
        request("/$type/$expo", "POST", body(type)).statusCode() shouldBe 201
    }

    private fun list(
        type: String,
        expo: String = EXPO,
    ): JsonNode = mapper.readTree(request("/$type/program/$expo", role = null).body())

    private fun readPolicy(id: Long): JsonNode = list("standard").first { it.get("id").asLong() == id }.get("policy")

    private fun update(
        id: Long,
        policy: String,
        revision: Long = 1,
    ): HttpResponse<String> = request("/standard/$id", "PATCH", body("standard", policy, id, revision))

    @Test
    fun `pending Expo change reserves its cover image through cleanup and competing attachments`() {
        create("standard")
        seed("STANDARD", 1)
        jdbc.update(
            "INSERT INTO tb_expo_image(id,storage_provider,object_key,public_url,content_type,uploaded_by,created_at,status) VALUES ('reserved','local','reserved','/image/reserved','image/png','1',CURRENT_TIMESTAMP - INTERVAL '2 days','PENDING')",
        )
        val patch =
            expoBody(body("standard", policy(capacity = 3), id = 1, revision = 1), null)
                .replace("\"coverImage\":\"\"", "\"coverImage\":\"/image/reserved\"")
        lostResponse = true
        request("/expo/$EXPO", "PATCH", patch).statusCode() shouldBe 502
        jdbc.queryForObject("SELECT reserved_expo_id FROM tb_expo_image WHERE id='reserved'", String::class.java) shouldBe EXPO
        val repository = imageRepository
        repository.markDeleting("reserved", java.time.Instant.now()) shouldBe 0
        request(
            "/expo/$OTHER",
            "PATCH",
            expoBody(null, null).replace("\"coverImage\":\"\"", "\"coverImage\":\"/image/reserved\""),
        ).statusCode() shouldBe
            409
        pending() shouldBe 1L
        lostResponse = false
        request("/expo/$EXPO/program-changes/retry", "POST").statusCode() shouldBe 204
        jdbc.queryForObject("SELECT status FROM tb_expo_image WHERE id='reserved'", String::class.java) shouldBe "ATTACHED"
        jdbc.queryForObject("SELECT reserved_expo_id FROM tb_expo_image WHERE id='reserved'", String::class.java) shouldBe null
        pending() shouldBe 0L
    }

    @Test
    fun `definite rejection releases image reservation and permits another Expo to attach it`() {
        create("standard")
        seed("STANDARD", 1, confirmed = 2)
        jdbc.update(
            "INSERT INTO tb_expo_image(id,storage_provider,object_key,public_url,content_type,uploaded_by,created_at,status) VALUES ('rejected','local','rejected','/image/rejected','image/png','1',CURRENT_TIMESTAMP,'PENDING')",
        )
        val patch =
            expoBody(body("standard", policy(capacity = 1), id = 1, revision = 1), null)
                .replace("\"coverImage\":\"\"", "\"coverImage\":\"/image/rejected\"")
        request("/expo/$EXPO", "PATCH", patch).statusCode() shouldBe 409
        pending() shouldBe 0L
        jdbc.queryForObject("SELECT reserved_expo_id FROM tb_expo_image WHERE id='rejected'", String::class.java) shouldBe null
        request(
            "/expo/$OTHER",
            "PATCH",
            expoBody(null, null).replace("\"coverImage\":\"\"", "\"coverImage\":\"/image/rejected\""),
        ).statusCode() shouldBe
            204
    }

    @Test
    fun `unchanged and stale programme updates do not call Form again`() {
        create("standard")
        calls.clear()
        malformedForm = true
        update(1, policy()).statusCode() shouldBe 204
        update(1, policy(capacity = 3), revision = 2).statusCode() shouldBe 409
        calls.isEmpty() shouldBe true
    }

    @Test
    fun `legacy training replacement rejects managed Expo before any supplier call`() {
        create("training")
        request("/training/$EXPO", "POST", body("training", policy = null)).statusCode() shouldBe 201
        calls.clear()
        request(
            "/training/application/list/trainee/$EXPO",
            "POST",
            """{"trainingId":"id","phoneNumber":"01012345678","name":"name","informationJson":"{}","personalInformationStatus":true,"trainingProIds":[2]}""",
            role = null,
        ).statusCode() shouldBe 409
        calls.isEmpty() shouldBe true
    }

    private fun pending(): Long = jdbc.queryForObject("SELECT count(*) FROM tb_program_change", Long::class.java)!!

    private fun seed(
        type: String,
        id: Long,
        confirmed: Int = 0,
        waiting: Int = 0,
    ) {
        jdbc.update(
            "INSERT INTO test_admission(program_type,program_id,capacity,confirmed,waiting,history) VALUES (?,?,2,?,?,?)",
            type,
            id,
            confirmed,
            waiting,
            confirmed + waiting > 0,
        )
    }

    private fun supplierCount(
        type: String,
        id: Long,
    ): Int = jdbc.queryForObject("SELECT confirmed FROM test_admission WHERE program_type=? AND program_id=?", Int::class.java, type, id)!!

    private fun admit(
        type: String,
        id: Long,
    ): Boolean {
        val observed = request("/internal/expo/$EXPO/standard-programs/$id", role = null, token = "expo-test-token")
        if (observed.statusCode() != 200) return false
        val revision = mapper.readTree(observed.body()).get("revision").asLong()
        return transactions.execute {
            val row = jdbc.queryForMap("SELECT * FROM test_admission WHERE program_type=? AND program_id=? FOR UPDATE", type, id)
            if (revision < (row["next_revision"] as Long) || (row["confirmed"] as Int) >= (row["capacity"] as Int)) return@execute false
            jdbc.update(
                "UPDATE test_admission SET confirmed=confirmed+1,history=true,blocked=false WHERE program_type=? AND program_id=?",
                type,
                id,
            )
            true
        }
    }

    private fun policy(
        capacity: Int = 2,
        waiting: Int = 1,
        occupation: String = "TEACHER",
        group: String? = null,
        form: String = STANDARD_FORM,
    ): String =
        """{"formId":"$form","allowedOccupations":[${if (occupation.isEmpty()) "" else "\"$occupation\""}],"capacity":$capacity,"waitingCapacity":$waiting,"applicationStartedAt":"2026-10-11T00:00:00Z","applicationEndedAt":"2026-10-12T00:00:00Z","cancelEndedAt":"2026-10-12T00:00:00Z","promotionEndedAt":"2026-10-12T00:00:00Z","closed":false${group?.let {
            ",\"groupId\":\"$it\""
        } ?: ""}}"""

    private fun body(
        type: String,
        policy: String? =
            policy(
                form =
                    if (type ==
                        "training"
                    ) {
                        TRAINING_FORM
                    } else {
                        STANDARD_FORM
                    },
            ),
        id: Long? = null,
        revision: Long? = null,
    ): String =
        """{"title":"session","startedAt":"2026-10-13 09:00","endedAt":"2026-10-13 10:00"${if (type == "training") ",\"category\":\"ESSENTIAL\"" else ""}${policy?.let {
            ",\"policy\":$it"
        } ?: ""}${id?.let { ",\"id\":$it" } ?: ""}${revision?.let { ",\"revision\":$it" } ?: ""}}"""

    private fun expoBody(
        standard: String?,
        training: String?,
    ): String =
        """{"title":"expo","description":"description","startedDay":"2026-10-11","finishedDay":"2026-10-12","location":"place","coverImage":"","x":"127","y":"37","updateStandardProRequestDto":[${standard ?: ""}],"updateTrainingProRequestDto":[${training ?: ""}]}"""

    private fun request(
        path: String,
        method: String = "GET",
        body: String = "",
        role: String? = "ROLE_ADMIN",
        token: String? = null,
    ): HttpResponse<String> {
        val builder =
            HttpRequest
                .newBuilder(URI.create("http://localhost:$port$path"))
                .header("Content-Type", "application/json")
                .timeout(java.time.Duration.ofSeconds(20))
        role?.let { builder.header("Authorization", "Bearer ${TestJwt.token(it)}") }
        token?.let { builder.header("X-Internal-Token", it) }
        return http.send(
            builder
                .method(
                    method,
                    if (body.isEmpty()) HttpRequest.BodyPublishers.noBody() else HttpRequest.BodyPublishers.ofString(body),
                ).build(),
            HttpResponse.BodyHandlers.ofString(),
        )
    }

    companion object {
        private const val EXPO = "0190abcd-0000-7000-8000-000000000001"
        private const val OTHER = "0190abcd-0000-7000-8000-000000000002"
        private const val STANDARD_FORM = "0190abcd-0000-7000-8000-000000000011"
        private const val TRAINING_FORM = "0190abcd-0000-7000-8000-000000000012"
        private lateinit var supplierJdbc: JdbcTemplate
        private lateinit var supplierTransactions: TransactionTemplate
        private lateinit var supplierMapper: ObjectMapper
        private val calls = CopyOnWriteArrayList<Pair<String, String>>()

        @Volatile private var lostResponse = false

        @Volatile private var wrongFormExpo = false

        @Volatile private var malformedForm = false

        @Volatile private var shortFormPeriod = false

        @Volatile private var beforeReply: (() -> Unit)? = null
        private val serverExecutor = Executors.newFixedThreadPool(4)
        private val server =
            HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
                createContext("/") { exchange ->
                    try {
                        val path = exchange.requestURI.path
                        val body = exchange.requestBody.readAllBytes().toString(Charsets.UTF_8)
                        calls += path to body
                        if (path.startsWith("/internal/program-forms/")) {
                            val id = path.substringAfterLast('/')
                            val type = if (id == TRAINING_FORM) "TRAINEE" else "STANDARD"
                            val expo = if (wrongFormExpo) OTHER else EXPO
                            val response =
                                if (malformedForm) {
                                    "{}"
                                } else {
                                    """{"id":"$id","expoId":"$expo","purpose":"PROGRAM","participantType":"$type","startDate":"2026-10-01T00:00:00Z","endDate":"${if (shortFormPeriod) "2026-10-11T00:00:00Z" else "2026-11-01T00:00:00Z"}"}"""
                                }
                            val bytes = response.toByteArray()
                            exchange.sendResponseHeaders(if (id in listOf(STANDARD_FORM, TRAINING_FORM)) 200 else 404, bytes.size.toLong())
                            exchange.responseBody.write(bytes)
                        } else if (path.contains("/program-changes/")) {
                            val status = prepare(path, body)
                            beforeReply?.invoke()
                            exchange.sendResponseHeaders(if (status == 204 && lostResponse) 503 else status, -1)
                        } else if (path.endsWith("/resolve")) {
                            val bytes = """{"participantId":42}""".toByteArray()
                            exchange.sendResponseHeaders(200, bytes.size.toLong())
                            exchange.responseBody.write(bytes)
                        } else {
                            exchange.sendResponseHeaders(204, -1)
                        }
                    } finally {
                        exchange.close()
                    }
                }
                executor = serverExecutor
                start()
            }

        private fun prepare(
            path: String,
            body: String,
        ): Int =
            supplierTransactions.execute {
                val previousBody =
                    supplierJdbc
                        .query(
                            "SELECT body FROM test_change_receipt WHERE id=?",
                            { r, _ -> r.getString(1) },
                            path,
                        ).firstOrNull()
                if (previousBody != null) return@execute if (previousBody == body) 204 else 409
                val mutations = supplierMapper.readTree(body).get("mutations").toList()
                for (mutation in mutations) {
                    val previous = mutation.get("previous")
                    val definition = mutation.get("definition")
                    if (previous.isNull) continue
                    val type = previous.get("programType").asString()
                    val id = previous.get("id").asLong()
                    val capacity = if (previous.get("policy").isNull) 2147483647 else previous.get("policy").get("capacity").asInt()
                    supplierJdbc.update(
                        "INSERT INTO test_admission(program_type,program_id,capacity) VALUES (?,?,?) ON CONFLICT DO NOTHING",
                        type,
                        id,
                        capacity,
                    )
                    val row =
                        supplierJdbc.queryForMap(
                            "SELECT * FROM test_admission WHERE program_type=? AND program_id=? FOR UPDATE",
                            type,
                            id,
                        )
                    if (definition.isNull) {
                        if (row["history"] == true) return@execute 409
                    } else {
                        val policy = definition.get("policy")
                        if (row["history"] == true && previous.get("groupId") != definition.get("groupId")) return@execute 409
                        if ((row["confirmed"] as Int) > policy.get("capacity").asInt() ||
                            (row["waiting"] as Int) > policy.get("waitingCapacity").asInt()
                        ) {
                            return@execute 409
                        }
                    }
                }
                mutations.forEach { mutation ->
                    val previous = mutation.get("previous")
                    if (!previous.isNull) {
                        val definition = mutation.get("definition")
                        supplierJdbc.update(
                            "UPDATE test_admission SET blocked=true,capacity=?,next_revision=? WHERE program_type=? AND program_id=?",
                            if (definition.isNull) 0 else definition.get("policy").get("capacity").asInt(),
                            previous.get("revision").asLong() + 1,
                            previous.get("programType").asString(),
                            previous.get("id").asLong(),
                        )
                    }
                }
                supplierJdbc.update("INSERT INTO test_change_receipt(id,body) VALUES (?,?)", path, body)
                204
            }

        @JvmStatic
        @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) {
            val url = "http://127.0.0.1:${server.address.port}"
            registry.add("JWT_PUBLIC_KEY") { TestJwt.publicKeyPem }
            registry.add("expo.standard.application-service-url") { url }
            registry.add("expo.standard.user-service-url") { url }
            registry.add("expo.standard.internal-token") { "application-token" }
            registry.add("expo.training.application-service-url") { url }
            registry.add("expo.training.internal-token") { "application-token" }
            registry.add("expo.form-service-url") { url }
            registry.add("expo.form.internal-token") { "form-token" }
            registry.add("expo.attention.service-url") { url }
            registry.add("expo.attention.internal-token") { "attendance-token" }
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
