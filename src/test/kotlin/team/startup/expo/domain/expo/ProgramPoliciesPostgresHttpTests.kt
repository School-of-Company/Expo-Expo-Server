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
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.context.annotation.Bean
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
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
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
        rejectChange = false
        beforeReply = null
        beforeFormReply = null
        wrongFormExpo = false
        unrestrictedForm = false
        wrongFormPurpose = false
        lateFormStart = false
        malformedForm = false
        shortFormPeriod = false
        formQuestions = "[]"
        formVersion = 1
        guardianSupport = true
        guardianPrepareSupport = true
        guardianSupportVersion = "1"
        guardianPrepareStatus = null
        calls.clear()
        jdbc.execute("TRUNCATE tb_program_form_change")
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
    fun `guardian policy round trips for both types without changing another session in the same group`() {
        formQuestions = guardianQuestions()
        listOf("standard", "training").forEach { type ->
            val form = if (type == "standard") STANDARD_FORM else TRAINING_FORM
            request("/$type/$EXPO", "POST", body(type, guardianPolicy(form = form))).statusCode() shouldBe 201
            val first = list(type)[0]
            val group = first.get("groupId").asString()
            request("/$type/$EXPO", "POST", body(type, policy(form = form, group = group))).statusCode() shouldBe 201
            first.get("policy").get("guardianPolicy").toString() shouldBe mapper.readTree(guardian()).toString()
            list(type)[1].get("policy").has("guardianPolicy") shouldBe false
            val path = if (type == "standard") "standard-programs/1" else "training-programs/batch"
            val method = if (type == "standard") "GET" else "POST"
            val definition = internal("/internal/expo/$EXPO/$path", method, if (type == "training") """{"programIds":[1]}""" else "")
            definition.statusCode() shouldBe 200
            val result = mapper.readTree(definition.body()).let { if (type == "training") it[0] else it }
            result.get("policy").get("guardianPolicy").toString() shouldBe mapper.readTree(guardian()).toString()
            internal("/internal/expo/$OTHER/$path", method, if (type == "training") """{"programIds":[1]}""" else "")
                .statusCode() shouldBe 404
            jdbc
                .queryForObject("SELECT policy_json FROM tb_${type}_program WHERE id=1", String::class.java)!!
                .contains("guardianPolicy") shouldBe true
        }
        val legacy = mapper.readTree(policy())
        legacy.has("guardianPolicy") shouldBe false
    }

    @Test
    fun `guardian references require the current version and elementary branch rather than matching titles`() {
        formQuestions = guardianQuestions()
        val original = formQuestions
        listOf(
            original.replace("\"id\":102", "\"id\":999"),
            original.replace("\"primary\":\"초등학생\"", "\"renamed\":\"초등학생\""),
            original.replace("\"g1\":\"1학년\"", "\"missing\":\"1학년\""),
            original.replace("\"requiredStatus\":true", "\"requiredStatus\":false"),
            original.replace("\"parentIndex\":0", "\"parentIndex\":1"),
            original.replace("\"triggerValue\":\"primary\"", "\"triggerValue\":\"other\""),
            original.replace("\"formType\":\"DROPDOWN\"", "\"formType\":\"SENTENCE\""),
        ).forEach { invalid ->
            formQuestions = invalid
            request("/standard/$EXPO", "POST", body("standard", guardianPolicy())).statusCode() shouldBe 400
        }
        formQuestions = original
        formVersion = 2
        request("/standard/$EXPO", "POST", body("standard", guardianPolicy())).statusCode() shouldBe 409
        formVersion = 1
        wrongFormExpo = true
        request("/standard/$EXPO", "POST", body("standard", guardianPolicy())).statusCode() shouldBe 400
        wrongFormExpo = false
        list("standard").size() shouldBe 0
        formQuestions = original.replace("초등학생", "초등학교 재학생").replace("학년", "학년 선택")
        request("/standard/$EXPO", "POST", body("standard", guardianPolicy())).statusCode() shouldBe 201
    }

    @Test
    fun `guardian ON and OFF changes preserve revisions and reject confirmed or waiting participants`() {
        formQuestions = guardianQuestions()
        create("standard")
        seed("STANDARD", 1, waiting = 1)
        update(1, guardianPolicy()).statusCode() shouldBe 409
        pending() shouldBe 0L
        list("standard")[0].get("revision").asLong() shouldBe 1L
        jdbc.update("UPDATE test_admission SET waiting=0")
        update(1, guardianPolicy()).statusCode() shouldBe 204
        calls.any { it.first.endsWith("/guardian-policy") && it.second.contains("guardianPolicy") } shouldBe true
        list("standard")[0].get("revision").asLong() shouldBe 2L
        jdbc.update("UPDATE test_admission SET confirmed=1")
        update(1, policy(), revision = 2).statusCode() shouldBe 409
        list("standard")[0].get("policy").has("guardianPolicy") shouldBe true
        jdbc.update("UPDATE test_admission SET confirmed=0")
        update(1, policy(), revision = 1).statusCode() shouldBe 409
        update(1, policy(), revision = 2).statusCode() shouldBe 204
        list("standard")[0].get("policy").has("guardianPolicy") shouldBe false
        list("standard")[0].get("revision").asLong() shouldBe 3L
    }

    @Test
    fun `unsupported Application cannot enable a guardian policy or silently approve its removal`() {
        formQuestions = guardianQuestions()
        guardianSupport = false
        request("/standard/$EXPO", "POST", body("standard", guardianPolicy())).statusCode() shouldBe 503
        list("standard").size() shouldBe 0
        guardianSupport = true
        guardianSupportVersion = "2"
        request("/standard/$EXPO", "POST", body("standard", guardianPolicy())).statusCode() shouldBe 503
        list("standard").size() shouldBe 0
        guardianSupportVersion = "1"
        create("standard")
        update(1, guardianPolicy()).statusCode() shouldBe 204
        guardianPrepareStatus = 404
        update(1, policy(), revision = 2).statusCode() shouldBe 503
        pending() shouldBe 1L
        guardianPrepareStatus = null
        guardianPrepareSupport = false
        request("/expo/$EXPO/program-changes/retry", "POST").statusCode() shouldBe 503
        pending() shouldBe 1L
        list("standard")[0].get("policy").has("guardianPolicy") shouldBe true
        guardianPrepareSupport = true
        request("/expo/$EXPO/program-changes/retry", "POST").statusCode() shouldBe 204
        list("standard")[0].get("policy").has("guardianPolicy") shouldBe false
    }

    @Test
    fun `guardian list creation is atomic when support is missing`() {
        formQuestions = guardianQuestions()
        guardianSupport = false
        listOf("standard", "training").forEach { type ->
            val form = if (type == "standard") STANDARD_FORM else TRAINING_FORM
            request("/$type/list/$EXPO", "POST", "[${body(type)},${body(type, guardianPolicy(form))}]").statusCode() shouldBe 503
            list(type).size() shouldBe 0
        }
    }

    @Test
    fun `guardian form version is rechecked after approval and the pending command can retry`() {
        formQuestions = guardianQuestions()
        request("/standard/$EXPO", "POST", body("standard", guardianPolicy())).statusCode() shouldBe 201
        beforeReply = { formVersion = 2 }
        val command = body("standard", guardianPolicy(), 1, 1).replace("\"session\"", "\"updated\"")
        request("/standard/1", "PATCH", command).statusCode() shouldBe 409
        pending() shouldBe 1L
        list("standard")[0].get("revision").asLong() shouldBe 1L
        beforeReply = null
        formVersion = 1
        request("/expo/$EXPO/program-changes/retry", "POST").statusCode() shouldBe 204
        list("standard")[0].get("revision").asLong() shouldBe 2L
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
    fun `nullable form participant type allows standard and training create and policy update`() {
        unrestrictedForm = true
        listOf("standard", "training").forEach { type ->
            request("/$type/$EXPO", "POST", body(type)).statusCode() shouldBe 201
            val form = if (type == "standard") STANDARD_FORM else TRAINING_FORM
            request("/$type/1", "PATCH", body(type, policy = policy(form = form, capacity = 3), id = 1, revision = 1)).statusCode() shouldBe
                204
            list(type)[0].get("revision").asLong() shouldBe 2L
            calls.count { it.first == "/internal/forms/by-id/$form" } shouldBe 3
        }
        calls.any { it.first.startsWith("/internal/program-forms/") } shouldBe false
    }

    @Test
    fun `unrestricted forms still enforce expo purpose and both period boundaries without rows`() {
        unrestrictedForm = true
        wrongFormExpo = true
        request("/standard/$EXPO", "POST", body("standard")).statusCode() shouldBe 400
        wrongFormExpo = false
        wrongFormPurpose = true
        request("/standard/$EXPO", "POST", body("standard")).statusCode() shouldBe 400
        wrongFormPurpose = false
        lateFormStart = true
        request("/standard/$EXPO", "POST", body("standard")).statusCode() shouldBe 400
        lateFormStart = false
        shortFormPeriod = true
        request("/standard/$EXPO", "POST", body("standard")).statusCode() shouldBe 400
        list("standard").size() shouldBe 0
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
        request("/training/$EXPO", "POST", body("training", policy = policy(form = STANDARD_FORM))).statusCode() shouldBe 400
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
    fun `pre and onsite capacities round trip for standard and training through updates and internal definitions`() {
        listOf("standard", "training").forEach { type ->
            val form = if (type == "standard") STANDARD_FORM else TRAINING_FORM
            val initial = policy(capacity = 30, pre = 20, onsite = 10, form = form)
            request("/$type/$EXPO", "POST", body(type, policy = initial)).statusCode() shouldBe 201
            list(type)[0].get("policy").get("preRegistrationCapacity").asInt() shouldBe 20
            list(type)[0].get("policy").get("onsiteCapacity").asInt() shouldBe 10
            list(type)[0].get("policy").has("channels") shouldBe false
            val desired = policy(capacity = 30, pre = 15, onsite = 15, form = form)
            request("/$type/1", "PATCH", body(type, policy = desired, id = 1, revision = 1)).statusCode() shouldBe 204
            val stored = mapper.readTree(jdbc.queryForObject("SELECT policy_json FROM tb_${type}_program WHERE id=1", String::class.java)!!)
            stored.get("capacity").asInt() shouldBe 30
            stored.get("preRegistrationCapacity").asInt() shouldBe 15
            stored.get("onsiteCapacity").asInt() shouldBe 15
            list(type)[0].get("policy") shouldBe stored
            val internal =
                if (type == "standard") {
                    mapper.readTree(request("/internal/expo/$EXPO/standard-programs/1", token = "expo-test-token", role = null).body())
                } else {
                    mapper.readTree(
                        request(
                            "/internal/expo/$EXPO/training-programs/batch",
                            "POST",
                            """{"programIds":[1]}""",
                            token = "expo-test-token",
                            role = null,
                        ).body(),
                    )[0]
                }
            internal.get("policy") shouldBe stored
            val forwarded = mapper.readTree(calls.last { it.first.contains("/program-changes/") }.second).get("mutations")[0]
            forwarded
                .get("previous")
                .get("policy")
                .get("preRegistrationCapacity")
                .asInt() shouldBe 20
            forwarded.get("definition").get("policy") shouldBe stored
        }
    }

    @Test
    fun `admission capacities are required nonnegative integers with matching totals and no role quotas in API and DB`() {
        listOf("standard", "training").forEach { type ->
            val form = if (type == "standard") STANDARD_FORM else TRAINING_FORM
            val valid = policy(capacity = 30, pre = 20, onsite = 10, form = form)
            val invalid =
                listOf(
                    valid.replace("\"preRegistrationCapacity\":20", "\"preRegistrationCapacity\":-1"),
                    valid.replace("\"onsiteCapacity\":10", "\"onsiteCapacity\":-1"),
                    valid.replace("\"preRegistrationCapacity\":20,", ""),
                    valid.replace("\"onsiteCapacity\":10,", ""),
                    valid.replace("\"preRegistrationCapacity\":20", "\"preRegistrationCapacity\":null"),
                    valid.replace("\"onsiteCapacity\":10", "\"onsiteCapacity\":null"),
                    policy(capacity = 30, pre = 0, onsite = 30, form = form)
                        .replace("\"preRegistrationCapacity\":0", "\"preRegistrationCapacity\":null"),
                    policy(capacity = 30, pre = 30, onsite = 0, form = form)
                        .replace("\"onsiteCapacity\":0", "\"onsiteCapacity\":null"),
                    valid.replace("\"preRegistrationCapacity\":20", "\"preRegistrationCapacity\":21"),
                    valid.replace("\"preRegistrationCapacity\":20", "\"preRegistrationCapacity\":20.5"),
                    valid.replace("\"onsiteCapacity\":10", "\"onsiteCapacity\":10.5"),
                    valid.replace("\"capacity\":30", "\"capacity\":30.5"),
                    valid.replace("\"preRegistrationCapacity\":20", "\"preRegistrationCapacity\":\"20\""),
                    valid.replace("\"preRegistrationCapacity\":20", "\"preRegistrationCapacity\":2147483648"),
                    valid.replace("\"closed\":false", "\"closed\":false,\"channels\":[]"),
                    policy(capacity = 0, pre = 0, onsite = 0, form = form),
                    policy(capacity = -2, pre = Int.MAX_VALUE, onsite = Int.MAX_VALUE, form = form),
                )
            invalid.forEach { p ->
                request("/$type/$EXPO", "POST", body(type, policy = p)).statusCode() shouldBe 400
                request("/$type/list/$EXPO", "POST", "[${body(type)},${body(type, policy = p)}]").statusCode() shouldBe 400
                list(type).size() shouldBe 0
            }
            request("/$type/$EXPO", "POST", body(type, policy = valid)).statusCode() shouldBe 201
            val groupId = list(type)[0].get("groupId").asString()
            invalid.forEach { p ->
                assertThrows(DataIntegrityViolationException::class.java) {
                    jdbc.update("UPDATE tb_${type}_program SET policy_json=? WHERE id=1", p.dropLast(1) + ",\"groupId\":\"$groupId\"}")
                }
            }
            request("/$type/1", "PATCH", body(type, policy(capacity = 1, pre = 0, onsite = 1, form = form), 1, 1)).statusCode() shouldBe 204
            list(type)[0].get("policy").get("preRegistrationCapacity").asInt() shouldBe 0
        }
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
    fun `changing only the admission split still requires Application approval and preserves the definition on rejection`() {
        listOf("standard", "training").forEach { type ->
            create(type)
            val form = if (type == "standard") STANDARD_FORM else TRAINING_FORM
            val desired = policy(pre = 1, onsite = 1, form = form)
            rejectChange = true
            request("/$type/1", "PATCH", body(type, desired, 1, 1)).statusCode() shouldBe 409
            list(type)[0].get("revision").asLong() shouldBe 1L
            list(type)[0].get("policy").get("preRegistrationCapacity").asInt() shouldBe 2
            list(type)[0].get("policy").get("onsiteCapacity").asInt() shouldBe 0
            pending() shouldBe 0L
            val forwarded = mapper.readTree(calls.last { it.first.contains("/program-changes/") }.second).get("mutations")[0]
            forwarded
                .get("previous")
                .get("policy")
                .get("preRegistrationCapacity")
                .asInt() shouldBe 2
            forwarded
                .get("definition")
                .get("policy")
                .get("onsiteCapacity")
                .asInt() shouldBe 1
            rejectChange = false
            request("/$type/1", "PATCH", body(type, desired, 1, 1)).statusCode() shouldBe 204
        }
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
    fun `bulk application period applies valid programs and reports failures and skips`() {
        create("standard")
        val lateDeadlines =
            policy()
                .replace("\"2026-10-12T00:00:00Z\",\"promotionEndedAt\"", "\"2026-10-20T00:00:00Z\",\"promotionEndedAt\"")
                .replace("\"promotionEndedAt\":\"2026-10-12T00:00:00Z\"", "\"promotionEndedAt\":\"2026-10-20T00:00:00Z\"")
        request("/standard/$EXPO", "POST", body("standard", policy = lateDeadlines)).statusCode() shouldBe 201
        request("/training/$EXPO", "POST", body("training", policy = null)).statusCode() shouldBe 201
        val path = "/expo/$EXPO/programs/application-period"
        val period = """{"applicationStartedAt":"2026-10-13T00:00:00Z","applicationEndedAt":"2026-10-14T00:00:00Z"}"""
        request(path, "PATCH", """{"applicationStartedAt":"2026-10-13T00:00:00Z"}""").statusCode() shouldBe 400
        request(path, "PATCH", """{"applicationStartedAt":null,"applicationEndedAt":null}""").statusCode() shouldBe 400
        request(path, "PATCH", """{"applicationStartedAt":"2026-10-14T00:00:00Z","applicationEndedAt":"2026-10-13T00:00:00Z"}""")
            .statusCode() shouldBe 400
        request(path, "PATCH", period, role = null).statusCode() shouldBe 401
        request("/expo/missing/programs/application-period", "PATCH", period).statusCode() shouldBe 404

        val applied = request(path, "PATCH", period)
        applied.statusCode() shouldBe 200
        val results =
            mapper.readTree(applied.body()).get("results").associate {
                "${it.get("programType").asString()}-${it.get("id").asLong()}" to
                    it
            }
        results.getValue("STANDARD-1").get("status").asString() shouldBe "FAILED"
        results.getValue("STANDARD-1").get("reason").asString() shouldBe "프로그램 모집 정책이 올바르지 않습니다."
        results.getValue("STANDARD-2").get("status").asString() shouldBe "UPDATED"
        results.getValue("TRAINING-1").get("status").asString() shouldBe "SKIPPED_NO_POLICY"
        readPolicy(1).get("applicationStartedAt").asString() shouldBe "2026-10-11T00:00:00Z"
        readPolicy(2).get("applicationStartedAt").asString() shouldBe "2026-10-13T00:00:00Z"
        readPolicy(2).get("applicationEndedAt").asString() shouldBe "2026-10-14T00:00:00Z"
        list("standard")[1].get("revision").asLong() shouldBe 2L

        val repeated = request(path, "PATCH", period)
        repeated.statusCode() shouldBe 200
        mapper
            .readTree(repeated.body())
            .get("results")
            .toList()
            .map { it.get("status").asString() } shouldBe
            listOf("FAILED", "UNCHANGED", "SKIPPED_NO_POLICY")
        pending() shouldBe 0L
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
        jdbc.queryForObject("SELECT count(*) FROM tb_admin_change_history WHERE expo_id=?", Long::class.java, id) shouldBe 1L
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
        jdbc.queryForList("SELECT type FROM tb_admin_change_history ORDER BY id", String::class.java) shouldBe
            listOf("PROGRAM_CREATE", "PROGRAM_CREATE", "PROGRAM_DELETE", "PROGRAM_DELETE")
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

    @Test
    fun `program policy history shares the commit and no-op retries add no records`() {
        create("standard")
        create("training")
        val id = list("standard").first().get("id").asLong()
        update(id, policy(capacity = 3)).statusCode() shouldBe 204
        update(id, policy(capacity = 3)).statusCode() shouldBe 204
        jdbc.queryForList("SELECT type FROM tb_admin_change_history ORDER BY id", String::class.java) shouldBe
            listOf("PROGRAM_CREATE", "PROGRAM_CREATE", "PROGRAM_UPDATE")
        jdbc.queryForList("SELECT admin_id FROM tb_admin_change_history ORDER BY id", Long::class.java) shouldBe listOf(1L, 1L, 1L)
        jdbc.execute("ALTER TABLE tb_admin_change_history ADD CONSTRAINT test_reject_history CHECK (type <> 'PROGRAM_UPDATE') NOT VALID")
        try {
            update(id, policy(capacity = 4), revision = 2).statusCode() shouldBe 500
            readPolicy(id).get("capacity").asInt() shouldBe 3
            jdbc.queryForObject("SELECT count(*) FROM tb_admin_change_history", Long::class.java) shouldBe 3L
        } finally {
            jdbc.execute("ALTER TABLE tb_admin_change_history DROP CONSTRAINT test_reject_history")
        }
        request("/expo/$EXPO/program-changes/retry", "POST").statusCode() shouldBe 204
        readPolicy(id).get("capacity").asInt() shouldBe 4
        jdbc.queryForObject("SELECT count(*) FROM tb_admin_change_history", Long::class.java) shouldBe 4L
    }

    private fun formChange(
        form: String = STANDARD_FORM,
        id: String = UUID.randomUUID().toString(),
    ): String = "/internal/forms/by-id/$form/changes/$id"

    private fun internal(
        path: String,
        method: String = "GET",
        body: String = "",
    ): HttpResponse<String> = request(path, method, body, role = null, token = "expo-test-token")

    private fun shrink(
        start: String = "2026-10-11T00:00:00Z",
        end: String = "2026-10-12T00:00:00Z",
    ): String = """{"kind":"SHRINK","startDate":"$start","endDate":"$end"}"""

    @Test
    fun `form usage is internally authenticated and unused forms return null bounds`() {
        val path = "/internal/forms/by-id/$STANDARD_FORM/usage"
        request(path, role = null).statusCode() shouldBe 401
        request(path, role = null, token = "wrong").statusCode() shouldBe 401
        request(path).statusCode() shouldBe 401
        val response = internal(path)
        response.statusCode() shouldBe 200
        val usage = mapper.readTree(response.body())
        usage.get("formId").asString() shouldBe STANDARD_FORM
        usage.get("referenced").asBoolean() shouldBe false
        usage.get("applicationStartedAt").isNull shouldBe true
        usage.get("applicationEndedAt").isNull shouldBe true
        val change = formChange()
        request(change, "PUT", """{"kind":"DELETE"}""", role = null).statusCode() shouldBe 401
        request(change, "DELETE", role = null).statusCode() shouldBe 401
        internal("/internal/forms/by-id/not-a-uuid/usage").statusCode() shouldBe 400
    }

    @Test
    fun `usage combines all standard and training sessions including closed and elapsed periods`() {
        create("standard")
        create("standard")
        create("training")
        val expanded =
            policy(group = list("training")[0].get("groupId").asString())
                .replace(
                    "2026-10-11T00:00:00Z",
                    "2026-10-02T00:00:00Z",
                ).replace("2026-10-12T00:00:00Z", "2026-10-20T00:00:00Z")
        jdbc.update("UPDATE tb_training_program SET policy_json=? WHERE id=1", expanded)
        val closed = policy(group = list("standard")[1].get("groupId").asString()).replace("\"closed\":false", "\"closed\":true")
        jdbc.update("UPDATE tb_standard_program SET policy_json=? WHERE id=2", closed)
        val usage = mapper.readTree(internal("/internal/forms/by-id/$STANDARD_FORM/usage").body())
        usage.get("referenced").asBoolean() shouldBe true
        usage.get("applicationStartedAt").asString() shouldBe "2026-10-02T00:00:00Z"
        usage.get("applicationEndedAt").asString() shouldBe "2026-10-20T00:00:00Z"
        internal(formChange(), "PUT", """{"kind":"DELETE"}""").statusCode() shouldBe 409
        internal(formChange(), "PUT", shrink()).statusCode() shouldBe 409
        internal(formChange(), "PUT", shrink("2026-10-02T00:00:00Z", "2026-10-20T00:00:00Z")).statusCode() shouldBe 200
    }

    @Test
    fun `shrink accepts inclusive bounds and rejects either cut without creating receipts`() {
        create("standard")
        create("training")
        internal(formChange(), "PUT", shrink(start = "2026-10-11T00:00:01Z")).statusCode() shouldBe 409
        internal(formChange(), "PUT", shrink(end = "2026-10-11T23:59:59Z")).statusCode() shouldBe 409
        internal(formChange(), "PUT", """{"kind":"SHRINK"}""").statusCode() shouldBe 400
        internal(formChange(), "PUT", shrink("2026-10-12T00:00:00Z", "2026-10-11T00:00:00Z")).statusCode() shouldBe 400
        internal(formChange(), "PUT", """{"kind":"DELETE","startDate":"2026-10-11T00:00:00Z"}""").statusCode() shouldBe 400
        jdbc.queryForObject("SELECT count(*) FROM tb_program_form_change", Long::class.java) shouldBe 0L
        val response = internal(formChange(), "PUT", shrink())
        response.statusCode() shouldBe 200
        mapper.readTree(response.body()).get("referenced").asBoolean() shouldBe true
        internal(formChange(TRAINING_FORM), "PUT", """{"kind":"DELETE"}""").statusCode() shouldBe 409
    }

    @Test
    fun `change keys replay exactly and release tombstones prevent delayed resurrection`() {
        val path = formChange()
        val first = internal(path, "PUT", """{"kind":"DELETE"}""")
        first.statusCode() shouldBe 200
        internal(path, "PUT", """{"kind":"DELETE"}""").body() shouldBe first.body()
        internal(path, "PUT", shrink()).statusCode() shouldBe 409
        internal(formChange(), "PUT", """{"kind":"DELETE"}""").statusCode() shouldBe 409
        internal(formChange(), "DELETE").statusCode() shouldBe 204
        request("/standard/$EXPO", "POST", body("standard")).statusCode() shouldBe 409
        internal(path, "DELETE").statusCode() shouldBe 204
        internal(path, "DELETE").statusCode() shouldBe 204
        internal(path, "PUT", """{"kind":"DELETE"}""").statusCode() shouldBe 409
        val cancelled = formChange()
        internal(cancelled, "DELETE").statusCode() shouldBe 204
        internal(cancelled, "PUT", """{"kind":"DELETE"}""").statusCode() shouldBe 409
        create("standard")
    }

    @Test
    fun `expired reservations cannot be renewed and keep connections blocked until release`() {
        val id = UUID.randomUUID().toString()
        val path = formChange(id = id)
        val response = internal(path, "PUT", """{"kind":"DELETE"}""")
        response.statusCode() shouldBe 200
        val expires = java.time.Instant.parse(mapper.readTree(response.body()).get("expiresAt").asString())
        (
            java.time.Duration
                .between(java.time.Instant.now(), expires)
                .seconds in 290..300
        ) shouldBe true
        jdbc.update(
            "UPDATE tb_program_form_change SET expires_at=CURRENT_TIMESTAMP - INTERVAL '1 second' WHERE change_id=?",
            UUID.fromString(id),
        )
        internal(path, "PUT", """{"kind":"DELETE"}""").statusCode() shouldBe 409
        internal(formChange(), "PUT", """{"kind":"DELETE"}""").statusCode() shouldBe 409
        request("/standard/$EXPO", "POST", body("standard")).statusCode() shouldBe 409
        internal(path, "DELETE").statusCode() shouldBe 204
        create("standard")
    }

    @Test
    fun `reservations block individual list nested create and nested update connections atomically`() {
        val standard = formChange()
        val training = formChange(TRAINING_FORM)
        internal(standard, "PUT", """{"kind":"DELETE"}""").statusCode() shouldBe 200
        internal(training, "PUT", """{"kind":"DELETE"}""").statusCode() shouldBe 200
        listOf("standard", "training").forEach { type ->
            request("/$type/$EXPO", "POST", body(type)).statusCode() shouldBe 409
            request("/$type/list/$EXPO", "POST", "[${body(type, policy = null)},${body(type)}]").statusCode() shouldBe 409
            list(type).size() shouldBe 0
        }
        request("/expo/$EXPO", "PATCH", expoBody(body("standard"), body("training"))).statusCode() shouldBe 409
        jdbc.update(
            "INSERT INTO tb_expo_image(id,storage_provider,object_key,public_url,content_type,uploaded_by,created_at,status) VALUES ('form-image','local','test','/image/form-test','image/png','1',CURRENT_TIMESTAMP,'PENDING')",
        )
        val nested =
            expoBody(body("standard"), body("training"))
                .replace("updateStandardProRequestDto", "addStandardProRequestDto")
                .replace("updateTrainingProRequestDto", "addTrainingProRequestDto")
                .replace("\"coverImage\":\"\"", "\"coverImage\":\"/image/form-test\"")
        request("/expo", "POST", nested).statusCode() shouldBe 409
        jdbc.queryForObject("SELECT count(*) FROM tb_expo", Long::class.java) shouldBe 2L
        internal(standard, "DELETE").statusCode() shouldBe 204
        internal(training, "DELETE").statusCode() shouldBe 204
        create("standard")
        create("training")
        internal(formChange(), "PUT", shrink()).statusCode() shouldBe 200
        internal(formChange(TRAINING_FORM), "PUT", shrink()).statusCode() shouldBe 200
        update(1, policy(capacity = 3)).statusCode() shouldBe 409
        request("/training/1", "PATCH", body("training", policy(form = TRAINING_FORM, capacity = 3), 1, 1)).statusCode() shouldBe 409
        request(
            "/expo/$EXPO",
            "PATCH",
            expoBody(body("standard", policy(capacity = 3), 1, 1), body("training", policy(form = TRAINING_FORM), 1, 1)),
        ).statusCode() shouldBe
            409
        list("standard")[0].get("revision").asLong() shouldBe 1L
        list("training")[0].get("revision").asLong() shouldBe 1L
    }

    @Test
    fun `pending revisions contribute usage and delayed approval cannot bypass a reservation`() {
        create("standard")
        seed("STANDARD", 1)
        lostResponse = true
        val expanded = policy(capacity = 3).replace("2026-10-11T00:00:00Z", "2026-10-03T00:00:00Z")
        update(1, expanded).statusCode() shouldBe 502
        val usage = mapper.readTree(internal("/internal/forms/by-id/$STANDARD_FORM/usage").body())
        usage.get("applicationStartedAt").asString() shouldBe "2026-10-03T00:00:00Z"
        internal(formChange(), "PUT", shrink()).statusCode() shouldBe 409
        val path = formChange()
        internal(path, "PUT", shrink("2026-10-03T00:00:00Z")).statusCode() shouldBe 200
        lostResponse = false
        request("/expo/$EXPO/program-changes/retry", "POST").statusCode() shouldBe 409
        pending() shouldBe 1L
        list("standard")[0].get("revision").asLong() shouldBe 1L
        internal(path, "DELETE").statusCode() shouldBe 204
        shortFormPeriod = true
        request("/expo/$EXPO/program-changes/retry", "POST").statusCode() shouldBe 400
        pending() shouldBe 1L
        shortFormPeriod = false
        request("/expo/$EXPO/program-changes/retry", "POST").statusCode() shouldBe 204
        list("standard")[0].get("revision").asLong() shouldBe 2L
    }

    @Test
    fun `reservation made while application approval is delayed blocks final apply`() {
        create("training")
        seed("TRAINING", 1)
        val path = formChange(TRAINING_FORM)
        beforeReply = { internal(path, "PUT", shrink()).statusCode() shouldBe 200 }
        request("/training/1", "PATCH", body("training", policy(form = TRAINING_FORM, capacity = 3), 1, 1)).statusCode() shouldBe 409
        pending() shouldBe 1L
        list("training")[0].get("revision").asLong() shouldBe 1L
        beforeReply = null
        internal(path, "DELETE").statusCode() shouldBe 204
        request("/expo/$EXPO/program-changes/retry", "POST").statusCode() shouldBe 204
        list("training")[0].get("revision").asLong() shouldBe 2L
    }

    @Test
    fun `reservation waits in Postgres until a validated connection commits`() {
        val formLookup = CountDownLatch(1)
        val reply = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        beforeFormReply = {
            formLookup.countDown()
            reply.await(5, TimeUnit.SECONDS)
        }
        try {
            val create = CompletableFuture.supplyAsync({ request("/standard/$EXPO", "POST", body("standard")).statusCode() }, executor)
            formLookup.await(5, TimeUnit.SECONDS) shouldBe true
            val reserve = CompletableFuture.supplyAsync({ internal(formChange(), "PUT", """{"kind":"DELETE"}""").statusCode() }, executor)
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
            var waiting = false
            while (!waiting && System.nanoTime() < deadline) {
                waiting =
                    jdbc.queryForObject(
                        "SELECT EXISTS(SELECT 1 FROM pg_stat_activity WHERE wait_event_type='Lock' AND query LIKE 'SELECT id FROM tb_program_form_guard%')",
                        Boolean::class.java,
                    )!!
                if (!waiting) Thread.sleep(10)
            }
            waiting shouldBe true
            reserve.isDone shouldBe false
            reply.countDown()
            create.get(10, TimeUnit.SECONDS) shouldBe 201
            reserve.get(10, TimeUnit.SECONDS) shouldBe 409
        } finally {
            reply.countDown()
            beforeFormReply = null
            executor.shutdownNow()
        }
    }

    @Test
    fun `Postgres serializes reservation and new connection across separate transactions`() {
        val executor = Executors.newFixedThreadPool(2)
        try {
            repeat(6) {
                jdbc.execute("TRUNCATE tb_program_form_change, tb_standard_program RESTART IDENTITY CASCADE")
                val start = CountDownLatch(1)
                val path = formChange()
                val create =
                    CompletableFuture.supplyAsync({
                        start.await()
                        request("/standard/$EXPO", "POST", body("standard")).statusCode()
                    }, executor)
                val reserve =
                    CompletableFuture.supplyAsync({
                        start.await()
                        internal(path, "PUT", """{"kind":"DELETE"}""").statusCode()
                    }, executor)
                start.countDown()
                val created = create.get(15, TimeUnit.SECONDS)
                val reserved = reserve.get(15, TimeUnit.SECONDS)
                ((created == 201 && reserved == 409) || (created == 409 && reserved == 200)) shouldBe true
                jdbc.queryForObject("SELECT count(*) FROM tb_standard_program", Long::class.java) shouldBe if (created == 201) 1L else 0L
            }
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun expoAndListWritesKeepSessionPlaces() {
        jdbc.update(
            "INSERT INTO tb_expo_image(id,storage_provider,object_key,public_url,content_type,uploaded_by,created_at,status) VALUES ('place-image','local','place-test','/image/place-test','image/png','1',CURRENT_TIMESTAMP,'PENDING')",
        )
        val standard = withPlace(body("standard", policy = null), "Hall S")
        val training = withPlace(body("training", policy = null), "Hall T")
        val createBody =
            expoBody(standard, training)
                .replace("updateStandardProRequestDto", "addStandardProRequestDto")
                .replace("updateTrainingProRequestDto", "addTrainingProRequestDto")
        val created = request("/expo", "POST", createBody.replace("\"coverImage\":\"\"", "\"coverImage\":\"/image/place-test\""))
        created.statusCode() shouldBe 201
        val expoId = mapper.readTree(created.body()).get("expoId").asString()
        val standardId = jdbc.queryForObject("SELECT id FROM tb_standard_program WHERE expo_id=?", Long::class.java, expoId)!!
        val trainingId = jdbc.queryForObject("SELECT id FROM tb_training_program WHERE expo_id=?", Long::class.java, expoId)!!
        val updated =
            expoBody(
                withPlace(body("standard", policy = null, id = standardId, revision = 1), "New S"),
                withPlace(body("training", policy = null, id = trainingId, revision = 1), "New T"),
            )
        request(
            "/expo/$expoId",
            "PATCH",
            updated.replace("\"coverImage\":\"\"", "\"coverImage\":\"/image/place-test\""),
        ).statusCode() shouldBe
            204
        listOf("standard" to "New S", "training" to "New T").forEach { (type, place) ->
            val public = request("/$type/program/$expoId", role = null)
            public.statusCode() shouldBe 200
            mapper.readTree(public.body())[0].get("place").asString() shouldBe place
            request("/$type/list/$EXPO", "POST", "[${withPlace(body(type, policy = null), place)}]").statusCode() shouldBe 201
            list(type)[0].get("place").asString() shouldBe place
        }
    }

    private fun withPlace(
        body: String,
        place: String,
    ): String = body.dropLast(1) + ",\"place\":\"$place\"}"

    @Test
    fun sessionPlacesSurviveGuardedUpdatesAndInternalReads() {
        listOf("standard", "training").forEach { type ->
            request("/$type/$EXPO", "POST", withPlace(body(type), "  Room A  ")).statusCode() shouldBe 201
            val first = list(type)[0]
            first.get("place").asString() shouldBe "Room A"
            val form = if (type == "standard") STANDARD_FORM else TRAINING_FORM
            val linked = body(type, policy(group = first.get("groupId").asString(), form = form))
            request("/$type/$EXPO", "POST", withPlace(linked, "Room B")).statusCode() shouldBe 201
            list(type)[1].get("place").asString() shouldBe "Room B"
            seed(type.uppercase(), 1, confirmed = 1)
            request("/$type/1", "PATCH", withPlace(body(type, id = 1, revision = 1), "Room C")).statusCode() shouldBe 204
            list(type)[0].get("revision").asLong() shouldBe 2L
            list(type)[0].get("place").asString() shouldBe "Room C"
            val payload = mapper.readTree(calls.last { it.first.contains("/program-changes/") }.second)
            payload
                .get("mutations")[0]
                .get("previous")
                .get("place")
                .asString() shouldBe "Room A"
            payload
                .get("mutations")[0]
                .get("definition")
                .get("place")
                .asString() shouldBe "Room C"
            request("/$type/1", "PATCH", withPlace(body(type, id = 1, revision = 1), "stale")).statusCode() shouldBe 409
            request("/$type/1", "PATCH", body(type, id = 1, revision = 2)).statusCode() shouldBe 204
            list(type)[0].get("place").asString() shouldBe "Room C"
            val internal =
                if (type == "standard") {
                    request("/internal/expo/$EXPO/standard-programs/1", role = null, token = "expo-test-token")
                } else {
                    request(
                        "/internal/expo/$EXPO/training-programs/batch",
                        "POST",
                        "{\"programIds\":[1]}",
                        role = null,
                        token = "expo-test-token",
                    )
                }
            internal.statusCode() shouldBe 200
            val node = mapper.readTree(internal.body())
            (if (type == "standard") node else node[0]).get("place").asString() shouldBe "Room C"
            request("/$type/1", "PATCH", withPlace(body(type, id = 1, revision = 2), "   ")).statusCode() shouldBe 204
            list(type)[0].get("place").isNull shouldBe true
            list(type)[1].get("place").asString() shouldBe "Room B"
        }
        val page = request("/internal/expo/$EXPO/program-sessions", role = null, token = "expo-test-token")
        page.statusCode() shouldBe 200
        mapper.readTree(page.body()).get("items").count { !it.get("place").isNull && it.get("place").asString() == "Room B" } shouldBe 2
    }

    @Test
    fun optionalPlacesSupportLegacyAndValidateLength() {
        listOf("standard", "training").forEach { type ->
            request("/$type/$EXPO", "POST", body(type, policy = null)).statusCode() shouldBe 201
            list(type)[0].get("place").isNull shouldBe true
            request("/$type/$EXPO", "POST", withPlace(body(type, policy = null), " ")).statusCode() shouldBe 201
            list(type)[1].get("place").isNull shouldBe true
            request("/$type/$EXPO", "POST", withPlace(body(type, policy = null), "x".repeat(255))).statusCode() shouldBe 201
            list(type)[2].get("place").asString().length shouldBe 255
            val invalid = withPlace(body(type, policy = null), "x".repeat(256))
            request("/$type/$EXPO", "POST", invalid).statusCode() shouldBe 400
            request("/$type/list/$EXPO", "POST", "[$invalid]").statusCode() shouldBe 400
            request("/$type/1", "PATCH", withPlace(body(type, policy = null, id = 1), "x".repeat(256))).statusCode() shouldBe 400
        }
    }

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
        pre: Int = capacity,
        onsite: Int = 0,
    ): String =
        """{"formId":"$form","allowedOccupations":[${if (occupation.isEmpty()) "" else "\"$occupation\""}],"capacity":$capacity,"preRegistrationCapacity":$pre,"onsiteCapacity":$onsite,"waitingCapacity":$waiting,"applicationStartedAt":"2026-10-11T00:00:00Z","applicationEndedAt":"2026-10-12T00:00:00Z","cancelEndedAt":"2026-10-12T00:00:00Z","promotionEndedAt":"2026-10-12T00:00:00Z","closed":false${group?.let {
            ",\"groupId\":\"$it\""
        } ?: ""}}"""

    private fun guardianPolicy(form: String = STANDARD_FORM): String =
        policy(form = form, occupation = "ELEMENTARY_STUDENT")
            .replace("[\"ELEMENTARY_STUDENT\"]", "[\"ELEMENTARY_STUDENT\",\"PARENT\"]")
            .replace("\"closed\":false", "\"closed\":false,\"guardianPolicy\":${guardian()}")

    private fun guardian(): String =
        """{"formVersion":1,"schoolTypeQuestionId":101,"elementarySchoolValue":"primary","gradeQuestionId":102,"gradeValues":{${(1..6).joinToString {
            "\"g$it\":$it"
        }}}}"""

    private fun guardianQuestions(): String =
        """[{"id":101,"title":"학교급","formType":"DROPDOWN","requiredStatus":true,"jsonData":{"primary":"초등학생","other":"그 외"},"otherJson":null},{"id":102,"title":"학년","formType":"DROPDOWN","requiredStatus":true,"jsonData":{${(1..6).joinToString {
            "\"g$it\":\"${it}학년\""
        }}},"otherJson":{"conditional":{"parentIndex":0,"triggerValue":"primary"}}}]"""

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

        @Volatile private var rejectChange = false

        @Volatile private var wrongFormExpo = false

        @Volatile private var unrestrictedForm = false

        @Volatile private var wrongFormPurpose = false

        @Volatile private var lateFormStart = false

        @Volatile private var malformedForm = false

        @Volatile private var shortFormPeriod = false

        @Volatile private var formQuestions = "[]"

        @Volatile private var formVersion = 1

        @Volatile private var guardianSupport = true

        @Volatile private var guardianPrepareSupport = true

        @Volatile private var guardianSupportVersion = "1"

        @Volatile private var guardianPrepareStatus: Int? = null

        @Volatile private var beforeReply: (() -> Unit)? = null

        @Volatile private var beforeFormReply: (() -> Unit)? = null
        private val serverExecutor = Executors.newFixedThreadPool(4)
        private val server =
            HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
                createContext("/") { exchange ->
                    try {
                        val path = exchange.requestURI.path
                        val body = exchange.requestBody.readAllBytes().toString(Charsets.UTF_8)
                        calls += path to body
                        if (path.startsWith("/internal/forms/by-id/")) {
                            val id = path.substringAfterLast('/')
                            val type = if (id == TRAINING_FORM) "TRAINEE" else "STANDARD"
                            val expo = if (wrongFormExpo) OTHER else EXPO
                            val purpose = if (wrongFormPurpose) "REGISTRATION" else "PROGRAM"
                            val participantType = if (unrestrictedForm) "null" else "\"$type\""
                            val startDate = if (lateFormStart) "2026-10-11T01:00:00Z" else "2026-10-01T00:00:00Z"
                            val response =
                                if (malformedForm) {
                                    "{}"
                                } else {
                                    """{"id":"$id","expoId":"$expo","purpose":"$purpose","version":$formVersion,"title":"form","informationText":"","dynamicForm":$formQuestions,"participantType":$participantType,"startDate":"$startDate","endDate":"${if (shortFormPeriod) "2026-10-11T00:00:00Z" else "2026-11-01T00:00:00Z"}"}"""
                                }
                            val bytes = response.toByteArray()
                            beforeFormReply?.invoke()
                            exchange.sendResponseHeaders(if (id in listOf(STANDARD_FORM, TRAINING_FORM)) 200 else 404, bytes.size.toLong())
                            exchange.responseBody.write(bytes)
                        } else if (path.endsWith("/program-policy-capabilities")) {
                            exchange.requestHeaders.getFirst("X-Internal-Token") shouldBe "application-token"
                            if (guardianSupport) exchange.responseHeaders.add("X-Program-Guardian-Policy-Version", guardianSupportVersion)
                            exchange.sendResponseHeaders(if (guardianSupport) 204 else 404, -1)
                        } else if (path.contains("/program-changes/")) {
                            val status = guardianPrepareStatus ?: if (rejectChange) 409 else prepare(path, body)
                            beforeReply?.invoke()
                            if (guardianPrepareSupport) exchange.responseHeaders.add("X-Program-Guardian-Policy-Version", "1")
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
                        val previousGuardian = previous.path("policy").path("guardianPolicy")
                        if (previousGuardian != policy.path("guardianPolicy") &&
                            ((row["confirmed"] as Int) > 0 || (row["waiting"] as Int) > 0)
                        ) {
                            return@execute 409
                        }
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
            registry.add("USER_INTERNAL_TOKEN") { "user-token" }
            registry.add("APPLICATION_INTERNAL_TOKEN") { "application-token" }
            registry.add("expo.training.application-service-url") { url }
            registry.add("expo.form-service-url") { url }
            registry.add("FORM_INTERNAL_TOKEN") { "form-token" }
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

    // Fixture expos run 2026-10-11..12 (Asia/Seoul); pin "today" so date checks do not fail after that period.
    @TestConfiguration
    class ClockConfig {
        @Bean
        fun testClock(): Clock = Clock.fixed(Instant.parse("2026-10-11T12:00:00+09:00"), ZoneId.of("Asia/Seoul"))
    }
}
