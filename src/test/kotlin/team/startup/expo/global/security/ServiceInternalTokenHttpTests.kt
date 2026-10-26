package team.startup.expo.global.security

import com.sun.net.httpserver.HttpServer
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer
import org.springframework.boot.test.context.assertj.AssertableApplicationContext
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
    fun `each receiver gets only its own token even when legacy tokens are configured`() {
        withServers { runner, calls ->
            runner.withPropertyValues(*(TARGET_TOKENS + LEGACY_TOKENS).toTypedArray()).run { context ->
                context.startupFailure shouldBe null
                callAll(context).forEach { (_, action) -> action() }
                calls shouldBe
                    listOf(
                        "user" to USER,
                        "application" to APPLICATION,
                        "user" to USER,
                        "application" to APPLICATION,
                        "application" to APPLICATION,
                        "application" to APPLICATION,
                        "form" to FORM,
                        "application" to APPLICATION,
                        "application" to APPLICATION,
                        "form" to FORM,
                        "user" to USER,
                        "attention" to ATTENTION,
                        "attention" to ATTENTION,
                        "attention" to ATTENTION,
                    )
                context.getBean(TrainingDependenciesClient::class.java).traineePreFormPeriod("expo")
                calls.last() shouldBe ("form" to null)
            }
        }
    }

    @Test
    fun `legacy tokens alone no longer authenticate outgoing calls and startup still succeeds`() {
        withServers { runner, calls ->
            runner.withPropertyValues(*LEGACY_TOKENS.toTypedArray()).run { context ->
                context.startupFailure shouldBe null
                assertUnconfigured(context, "user", "application", "form", "attention")
                calls shouldBe emptyList()
            }
        }
    }

    @Test
    fun `explicit blank target tokens reject calls without sending requests`() {
        withServers { runner, calls ->
            runner
                .withPropertyValues(
                    "USER_INTERNAL_TOKEN=",
                    "APPLICATION_INTERNAL_TOKEN=",
                    "FORM_INTERNAL_TOKEN=",
                    "ATTENTION_INTERNAL_TOKEN=",
                    *LEGACY_TOKENS.toTypedArray(),
                ).run { context ->
                    context.startupFailure shouldBe null
                    assertUnconfigured(context, "user", "application", "form", "attention")
                    calls shouldBe emptyList()
                }
        }
    }

    @Test
    fun `partial target configuration serves configured receivers and rejects the rest`() {
        withServers { runner, calls ->
            runner.withPropertyValues("USER_INTERNAL_TOKEN=$USER", *LEGACY_TOKENS.toTypedArray()).run { context ->
                context.startupFailure shouldBe null
                callAll(context).filter { it.first == "user" }.forEach { (_, action) -> action() }
                calls shouldBe List(3) { "user" to USER }
                assertUnconfigured(context, "application", "form", "attention")
                calls.size shouldBe 3
            }
        }
    }

    @Test
    fun `receiver 401 for a mismatched token is reported as failure`() {
        withServers { runner, calls ->
            runner
                .withPropertyValues(
                    "USER_INTERNAL_TOKEN=$APPLICATION",
                    "APPLICATION_INTERNAL_TOKEN=$FORM",
                    "FORM_INTERNAL_TOKEN=$ATTENTION",
                    "ATTENTION_INTERNAL_TOKEN=$USER",
                ).run { context ->
                    context.startupFailure shouldBe null
                    callAll(context).forEach { (_, action) ->
                        assertThrows(ExpectedException::class.java) { action() }.status shouldBe HttpStatus.BAD_GATEWAY
                    }
                    calls.size shouldBe 12
                }
        }
    }

    private fun assertUnconfigured(
        context: AssertableApplicationContext,
        vararg targets: String,
    ) {
        val deletion = context.getBean(ExpoDeletionClient::class.java)
        assertThrows(ExpectedException::class.java) { deletion.requireConfiguration() }.status shouldBe HttpStatus.SERVICE_UNAVAILABLE
        callAll(context).filter { it.first in targets }.forEach { (_, action) ->
            assertThrows(ExpectedException::class.java) { action() }.status shouldBe HttpStatus.SERVICE_UNAVAILABLE
        }
        if ("application" in targets) {
            assertThrows(ExpectedException::class.java) {
                context.getBean(TrainingDependenciesClient::class.java).requireApplicationConfiguration()
            }.status shouldBe HttpStatus.SERVICE_UNAVAILABLE
        }
    }

    // ExpoDeletionClient keeps Application -> Form -> User -> Attention order, as DeleteExpoServiceImpl calls it.
    private fun callAll(context: AssertableApplicationContext): List<Pair<String, () -> Unit>> {
        val standard = context.getBean(StandardDependenciesClient::class.java)
        val training = context.getBean(TrainingDependenciesClient::class.java)
        val preregister = context.getBean(PreregisterSessionChangesClient::class.java)
        val policy = context.getBean(ProgramPolicyClient::class.java)
        val deletion = context.getBean(ExpoDeletionClient::class.java)
        return listOf(
            "user" to { standard.resolveParticipant("expo", "01000000000") },
            "application" to { standard.applications(1) },
            "user" to { training.resolveTrainee("expo", "training") },
            "application" to { training.applications(1) },
            "application" to { preregister.prepare("expo", 1, 2, "UPDATE", true, null) },
            "application" to { preregister.adoptWaitingCapacity("expo", 1, 0) },
            "form" to { policy.validateForm(definition()) },
            "application" to { policy.prepare("expo", UUID.randomUUID(), payload()) },
            "application" to { deletion.deleteApplications("expo", listOf(1), listOf(2)) },
            "form" to { deletion.deleteForms("expo") },
            "user" to { deletion.deleteUsers("expo") },
            "attention" to { deletion.deleteAttendances("expo", listOf(1), listOf(2)) },
        )
    }

    private fun withServers(action: (ApplicationContextRunner, MutableList<Pair<String, String?>>) -> Unit) {
        val calls = mutableListOf<Pair<String, String?>>()
        val expected = mapOf("user" to USER, "application" to APPLICATION, "form" to FORM, "attention" to ATTENTION)
        val servers =
            expected.mapValues { (service, token) ->
                HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
                    createContext("/") { exchange ->
                        val supplied = exchange.requestHeaders.getFirst("X-Internal-Token")
                        synchronized(calls) { calls.add(service to supplied) }
                        exchange.requestBody.use { it.readAllBytes() }
                        val path = exchange.requestURI.path
                        val publicForm = service == "form" && path.startsWith("/forms/")
                        val body =
                            when {
                                !publicForm && supplied != token -> {
                                    null
                                }

                                path.endsWith("/resolve") -> {
                                    """{"participantId":1,"traineeId":1}"""
                                }

                                path.startsWith("/internal/forms/by-id/") -> {
                                    """{"id":"$FORM_ID","expoId":"expo","purpose":"PROGRAM","participantType":"STANDARD","startDate":"$START","endDate":"$END"}"""
                                }

                                publicForm -> {
                                    """{"startDate":"$START","endDate":"$END"}"""
                                }

                                path.contains("/program/") -> {
                                    "[]"
                                }

                                else -> {
                                    ""
                                }
                            }
                        val status =
                            if (body == null) {
                                401
                            } else if (body.isEmpty()) {
                                204
                            } else {
                                200
                            }
                        exchange.sendResponseHeaders(status, if (body.isNullOrEmpty()) -1 else body.toByteArray().size.toLong())
                        exchange.responseBody.use { if (!body.isNullOrEmpty()) it.write(body.toByteArray()) }
                        exchange.close()
                    }
                    start()
                }
            }
        try {
            fun url(service: String) = "http://127.0.0.1:${servers.getValue(service).address.port}"
            val runner =
                ApplicationContextRunner()
                    // Load application.yaml so legacy environment variable names are exercised through the real mapping.
                    .withInitializer(ConfigDataApplicationContextInitializer())
                    .withBean(tools.jackson.databind.ObjectMapper::class.java, { jacksonObjectMapper() })
                    .withBean(team.startup.expo.global.client.DownstreamCircuitBreakers::class.java)
                    .withUserConfiguration(
                        StandardDependenciesClient::class.java,
                        TrainingDependenciesClient::class.java,
                        ExpoDeletionClient::class.java,
                        PreregisterSessionChangesClient::class.java,
                        ProgramPolicyClient::class.java,
                        ProgramAttendanceClient::class.java,
                    ).withPropertyValues(
                        "expo.standard.user-service-url=${url("user")}",
                        "expo.standard.application-service-url=${url("application")}",
                        "expo.training.user-service-url=${url("user")}",
                        "expo.training.application-service-url=${url("application")}",
                        "expo.form-service-url=${url("form")}",
                        "expo.attention.service-url=${url("attention")}",
                    )
            action(runner, calls)
        } finally {
            servers.values.forEach { it.stop(0) }
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
                    preRegistrationCapacity = 1,
                    onsiteCapacity = 0,
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
        const val USER = "fake-user-token"
        const val APPLICATION = "fake-application-token"
        const val FORM = "fake-form-token"
        const val ATTENTION = "fake-attention-token"
        val TARGET_TOKENS =
            listOf(
                "USER_INTERNAL_TOKEN=$USER",
                "APPLICATION_INTERNAL_TOKEN=$APPLICATION",
                "FORM_INTERNAL_TOKEN=$FORM",
                "ATTENTION_INTERNAL_TOKEN=$ATTENTION",
            )

        // Former common variables and the properties the removed nested @Value fallbacks read.
        val LEGACY_TOKENS =
            listOf(
                "STANDARD_INTERNAL_TOKEN=legacy-standard",
                "TRAINING_INTERNAL_TOKEN=legacy-training",
                "EXPO_DELETE_INTERNAL_TOKEN=legacy-delete",
                "expo.standard.internal-token=legacy-standard",
                "expo.training.internal-token=legacy-training",
                "expo.delete-internal-token=legacy-delete",
                "expo.form.internal-token=legacy-form",
            )
        val FORM_ID: UUID = UUID.fromString("00000000-0000-0000-0000-000000000001")
        val START: Instant = Instant.parse("2026-10-11T00:00:00Z")
        val END: Instant = Instant.parse("2026-10-12T00:00:00Z")
    }
}
