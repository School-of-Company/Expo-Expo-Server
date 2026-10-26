package team.startup.expo.domain.expo

import com.sun.net.httpserver.HttpServer
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import team.startup.expo.domain.expo.service.PreregisterSessionChangesClient
import team.startup.expo.global.client.DownstreamCircuitBreakers
import team.startup.expo.global.exception.ExpectedException
import tools.jackson.databind.ObjectMapper
import java.net.InetSocketAddress

class PreregisterSessionReconciliationClientTests {
    @Test
    fun `only an explicit completed response succeeds and retry is idempotent`() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        var status = 200
        var body = "true"
        var calls = 0
        server.createContext("/internal/expos/expo/preregister-sessions/5/reconciliation") { exchange ->
            exchange.requestMethod shouldBe "POST"
            exchange.requestHeaders.getFirst("X-Internal-Token") shouldBe "application-token"
            exchange.requestBody.readAllBytes().size shouldBe 0
            calls++
            val bytes = body.toByteArray()
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
            exchange.close()
        }
        server.start()
        try {
            val client =
                PreregisterSessionChangesClient(
                    "http://127.0.0.1:${server.address.port}",
                    "application-token",
                    ObjectMapper(),
                    DownstreamCircuitBreakers(),
                )
            repeat(2) { client.reconcile("expo", 5) }
            calls shouldBe 2
            listOf(
                Triple(200, "false", HttpStatus.CONFLICT),
                Triple(409, "false", HttpStatus.CONFLICT),
                Triple(200, "{}", HttpStatus.BAD_GATEWAY),
                Triple(200, "\"true\"", HttpStatus.BAD_GATEWAY),
                Triple(500, "true", HttpStatus.BAD_GATEWAY),
            ).forEach { (code, response, expected) ->
                status = code
                body = response
                assertThrows(ExpectedException::class.java) { client.reconcile("expo", 5) }.status shouldBe expected
            }
            assertThrows(ExpectedException::class.java) {
                PreregisterSessionChangesClient("", "application-token", ObjectMapper(), DownstreamCircuitBreakers()).reconcile("expo", 5)
            }.status shouldBe HttpStatus.SERVICE_UNAVAILABLE
            assertThrows(ExpectedException::class.java) {
                PreregisterSessionChangesClient(
                    "http://127.0.0.1:${server.address.port}",
                    "",
                    ObjectMapper(),
                    DownstreamCircuitBreakers(),
                ).reconcile("expo", 5)
            }.status shouldBe HttpStatus.SERVICE_UNAVAILABLE
        } finally {
            server.stop(0)
        }
    }
}
