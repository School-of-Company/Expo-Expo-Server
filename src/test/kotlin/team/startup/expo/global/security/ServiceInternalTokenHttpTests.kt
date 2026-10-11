package team.startup.expo.global.security

import com.sun.net.httpserver.HttpServer
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.http.HttpStatus
import team.startup.expo.domain.expo.presentation.dto.request.ProgramOccupation
import team.startup.expo.domain.expo.presentation.dto.request.ProgramPolicyRequest
import team.startup.expo.domain.expo.presentation.dto.response.ProgramChangePayload
import team.startup.expo.domain.expo.presentation.dto.response.ProgramDefinition
import team.startup.expo.domain.expo.presentation.dto.response.ProgramMutation
import team.startup.expo.domain.expo.presentation.dto.response.ProgramType
import team.startup.expo.domain.expo.service.ExpoDeletionClient
import team.startup.expo.domain.expo.service.PreregisterSessionChangesClient
import team.startup.expo.domain.expo.service.ProgramPolicyClient
import team.startup.expo.domain.standard.service.StandardDependenciesClient
import team.startup.expo.domain.training.service.TrainingDependenciesClient
import team.startup.expo.global.attendance.ProgramAttendanceClient
import team.startup.expo.global.exception.ExpectedException
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.net.InetSocketAddress
import java.time.Instant
import java.util.UUID

class ServiceInternalTokenHttpTests {
    @Test
    fun `explicit receiver tokens override conflicting legacy tokens even with the same service URL`() {
        verifyCalls(
            listOf(
                "USER_INTERNAL_TOKEN=receiver-user",
                "APPLICATION_INTERNAL_TOKEN=receiver-application",
                "FORM_INTERNAL_TOKEN=receiver-form",
            ),
            "receiver-user",
            "receiver-application",
            "receiver-user",
            "receiver-application",
            "receiver-application",
            "receiver-form",
            "receiver-application",
            "receiver-application",
            "receiver-form",
            "receiver-user",
        )
    }

    @Test
    fun `absent receiver tokens retain each callers legacy configuration`() {
        verifyCalls(
            emptyList(),
            "legacy-standard",
            "legacy-standard",
            "legacy-training",
            "legacy-training",
            "legacy-standard",
            "legacy-form",
            "legacy-standard",
            "legacy-delete",
            "legacy-delete",
            "legacy-delete",
        )
    }

    @Test
    fun `partial receiver configuration falls back only for absent targets`() {
        verifyCalls(
            listOf("USER_INTERNAL_TOKEN=receiver-user"),
            "receiver-user",
            "legacy-standard",
            "receiver-user",
            "legacy-training",
            "legacy-standard",
            "legacy-form",
            "legacy-standard",
            "legacy-delete",
            "legacy-delete",
            "receiver-user",
        )
    }

    @Test
    fun `explicit blank receiver tokens reject calls without falling back`() {
        withServer { runner, calls ->
            runner.withPropertyValues("USER_INTERNAL_TOKEN=", "APPLICATION_INTERNAL_TOKEN=", "FORM_INTERNAL_TOKEN=").run { context ->
                context.startupFailure shouldBe null
                val standard = context.getBean(StandardDependenciesClient::class.java)
                val training = context.getBean(TrainingDependenciesClient::class.java)
                val policy = context.getBean(ProgramPolicyClient::class.java)
                val actions =
                    listOf<() -> Unit>(
                        { standard.resolveParticipant("expo", "01000000000") },
                        { standard.applications(1) },
                        { training.resolveTrainee("expo", "training") },
                        { training.requireApplicationConfiguration() },
                        { context.getBean(ExpoDeletionClient::class.java).requireConfiguration() },
                        { context.getBean(PreregisterSessionChangesClient::class.java).prepare("expo", 1, 2, "UPDATE", true, null) },
                        { policy.validateForm(definition()) },
                        { policy.prepare("expo", UUID.randomUUID(), payload()) },
                    )
                actions.forEach { action ->
                    assertThrows(ExpectedException::class.java) { action() }.status shouldBe HttpStatus.SERVICE_UNAVAILABLE
                }
                calls shouldBe emptyList()
            }
        }
    }

    private fun verifyCalls(
        properties: List<String>,
        vararg expected: String,
    ) {
        withServer { runner, calls ->
            runner.withPropertyValues(*properties.toTypedArray()).run { context ->
                context.startupFailure shouldBe null
                val standard = context.getBean(StandardDependenciesClient::class.java)
                standard.resolveParticipant("expo", "01000000000")
                standard.applications(1)
                val training = context.getBean(TrainingDependenciesClient::class.java)
                training.resolveTrainee("expo", "training")
                training.applications(1)
                context.getBean(PreregisterSessionChangesClient::class.java).prepare("expo", 1, 2, "UPDATE", true, null)
                val policy = context.getBean(ProgramPolicyClient::class.java)
                policy.validateForm(definition())
                policy.prepare("expo", UUID.randomUUID(), payload())
                val deletion = context.getBean(ExpoDeletionClient::class.java)
                deletion.requireConfiguration()
                deletion.deleteApplications("expo", listOf(1), listOf(2))
                deletion.deleteForms("expo")
                deletion.deleteUsers("expo")
                calls shouldBe expected.toList()
                training.traineePreFormPeriod("expo")
                calls.last() shouldBe null
            }
        }
    }

    private fun withServer(action: (ApplicationContextRunner, MutableList<String?>) -> Unit) {
        val calls = mutableListOf<String?>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            calls.add(exchange.requestHeaders.getFirst("X-Internal-Token"))
            exchange.requestBody.use { it.readAllBytes() }
            val path = exchange.requestURI.path
            val body =
                when {
                    path.endsWith("/resolve") -> {
                        """{"participantId":1,"traineeId":1}"""
                    }

                    path.startsWith("/internal/program-forms/") -> {
                        """{"id":"$FORM_ID","expoId":"expo","purpose":"PROGRAM","participantType":"STANDARD","startDate":"$START","endDate":"$END"}"""
                    }

                    path.startsWith("/forms/") -> {
                        """{"startDate":"$START","endDate":"$END"}"""
                    }

                    path.contains("/program/") -> {
                        "[]"
                    }

                    else -> {
                        ""
                    }
                }
            val status = if (body.isEmpty()) 204 else 200
            exchange.sendResponseHeaders(status, if (status == 204) -1 else body.toByteArray().size.toLong())
            exchange.responseBody.use { if (body.isNotEmpty()) it.write(body.toByteArray()) }
            exchange.close()
        }
        server.start()
        try {
            val url = "http://127.0.0.1:${server.address.port}"
            val runner =
                ApplicationContextRunner()
                    .withBean(tools.jackson.databind.ObjectMapper::class.java, { jacksonObjectMapper() })
                    .withUserConfiguration(
                        StandardDependenciesClient::class.java,
                        TrainingDependenciesClient::class.java,
                        ExpoDeletionClient::class.java,
                        PreregisterSessionChangesClient::class.java,
                        ProgramPolicyClient::class.java,
                        ProgramAttendanceClient::class.java,
                    ).withPropertyValues(
                        "expo.standard.user-service-url=$url",
                        "expo.standard.application-service-url=$url",
                        "expo.training.user-service-url=$url",
                        "expo.training.application-service-url=$url",
                        "expo.form-service-url=$url",
                        "expo.attention.service-url=$url",
                        "expo.standard.internal-token=legacy-standard",
                        "expo.training.internal-token=legacy-training",
                        "expo.delete-internal-token=legacy-delete",
                        "expo.form.internal-token=legacy-form",
                        "expo.attention.internal-token=attendance",
                    )
            action(runner, calls)
        } finally {
            server.stop(0)
        }
    }

    private fun definition() =
        ProgramDefinition(
            expoId = "expo",
            programType = ProgramType.STANDARD,
            id = 1,
            title = "program",
            startedAt = "10:00",
            endedAt = "11:00",
            groupId = UUID.randomUUID(),
            policy =
                ProgramPolicyRequest(
                    formId = FORM_ID,
                    allowedOccupations = setOf(ProgramOccupation.TEACHER),
                    capacity = 1,
                    waitingCapacity = 0,
                    applicationStartedAt = START,
                    applicationEndedAt = END,
                    cancelEndedAt = END,
                    promotionEndedAt = END,
                    closed = false,
                ),
            revision = 1,
        )

    private fun payload() = definition().let { ProgramChangePayload(listOf(ProgramMutation(it, it.copy(revision = 2)))) }

    private companion object {
        val FORM_ID: UUID = UUID.fromString("00000000-0000-0000-0000-000000000001")
        val START: Instant = Instant.parse("2026-10-11T00:00:00Z")
        val END: Instant = Instant.parse("2026-10-12T00:00:00Z")
    }
}
