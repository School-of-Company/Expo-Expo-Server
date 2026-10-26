package team.startup.expo.global.client

import com.sun.net.httpserver.HttpServer
import io.github.resilience4j.circuitbreaker.CircuitBreaker
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import team.startup.expo.domain.expo.service.AdminAuthorityClient
import team.startup.expo.domain.expo.service.PreregisterSessionChangesClient
import team.startup.expo.global.exception.ExpectedException
import tools.jackson.databind.ObjectMapper
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger

/** 서비스 간 호출의 회로 차단과 재시도 규칙을 실제 HTTP 서버로 확인한다. */
class DownstreamCircuitBreakersTests {
    private lateinit var server: HttpServer
    private lateinit var breakers: DownstreamCircuitBreakers
    private val calls = AtomicInteger()
    private val methods = mutableListOf<String>()

    @Volatile private var status = 200

    private val url get() = "http://127.0.0.1:${server.address.port}"

    @BeforeEach
    fun setUp() {
        breakers = DownstreamCircuitBreakers()
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            calls.incrementAndGet()
            synchronized(methods) { methods.add(exchange.requestMethod) }
            exchange.requestBody.readAllBytes()
            val bytes = "true".toByteArray()
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
            exchange.close()
        }
        server.start()
    }

    @AfterEach
    fun tearDown() {
        server.stop(0)
    }

    private fun admin() = AdminAuthorityClient(url, breakers)

    private fun preregister() = PreregisterSessionChangesClient(url, "token", ObjectMapper(), breakers)

    @Test
    fun `서비스가 계속 5xx면 회로가 열려 호출 없이 503으로 바로 실패한다`() {
        status = 500
        repeat(10) {
            assertThrows(ExpectedException::class.java) { admin().requireCurrentAdmin("Bearer t") }.status shouldBe
                HttpStatus.SERVICE_UNAVAILABLE
        }
        breakers.of(Downstream.USER).state shouldBe CircuitBreaker.State.OPEN

        val before = calls.get()
        val error = assertThrows(ExpectedException::class.java) { admin().requireCurrentAdmin("Bearer t") }
        error.status shouldBe HttpStatus.SERVICE_UNAVAILABLE
        calls.get() shouldBe before
    }

    @Test
    fun `회로는 서비스마다 따로여서 User가 죽어도 Application 호출은 막히지 않는다`() {
        status = 500
        repeat(10) { assertThrows(ExpectedException::class.java) { admin().requireCurrentAdmin("Bearer t") } }
        breakers.of(Downstream.USER).state shouldBe CircuitBreaker.State.OPEN

        status = 200
        preregister().reconcile("expo", 5)
        breakers.of(Downstream.APPLICATION).state shouldBe CircuitBreaker.State.CLOSED
    }

    @Test
    fun `정상 거절인 4xx는 아무리 반복해도 회로를 열지 않는다`() {
        listOf(401, 403).forEach { code ->
            status = code
            repeat(30) { assertThrows(ExpectedException::class.java) { admin().requireCurrentAdmin("Bearer t") } }
        }
        status = 409
        repeat(30) { assertThrows(ExpectedException::class.java) { preregister().reconcile("expo", 5) } }

        breakers.of(Downstream.USER).state shouldBe CircuitBreaker.State.CLOSED
        breakers.of(Downstream.APPLICATION).state shouldBe CircuitBreaker.State.CLOSED
    }

    @Test
    fun `쓰기는 연결 실패여도 재시도하지 않고 GET만 한 번 재시도한다`() {
        val closedPort = ServerSocket(0).use { it.localPort }
        val dead = "http://127.0.0.1:$closedPort"

        assertThrows(ExpectedException::class.java) {
            PreregisterSessionChangesClient(dead, "token", ObjectMapper(), breakers).reconcile("expo", 5)
        }.status shouldBe HttpStatus.SERVICE_UNAVAILABLE
        breakers.of(Downstream.APPLICATION).metrics.numberOfFailedCalls shouldBe 1

        assertThrows(ExpectedException::class.java) { AdminAuthorityClient(dead, breakers).requireCurrentAdmin("Bearer t") }
            .status shouldBe HttpStatus.SERVICE_UNAVAILABLE
        breakers.of(Downstream.USER).metrics.numberOfFailedCalls shouldBe 1
    }

    @Test
    fun `연결 거부 GET은 사용자 요청 1건이 회로에 1건으로 집계되어 10번째 요청에서 열린다`() {
        val closedPort = ServerSocket(0).use { it.localPort }
        val client = AdminAuthorityClient("http://127.0.0.1:$closedPort", breakers)

        repeat(9) { index ->
            assertThrows(ExpectedException::class.java) { client.requireCurrentAdmin("Bearer t") }
            breakers.of(Downstream.USER).metrics.numberOfFailedCalls shouldBe index + 1
            breakers.of(Downstream.USER).state shouldBe CircuitBreaker.State.CLOSED
        }
        assertThrows(ExpectedException::class.java) { client.requireCurrentAdmin("Bearer t") }.status shouldBe
            HttpStatus.SERVICE_UNAVAILABLE
        breakers.of(Downstream.USER).state shouldBe CircuitBreaker.State.OPEN
    }

    @Test
    fun `재시도가 성공하면 첫 시도의 실패는 회로에 남지 않는다`() {
        val port = ServerSocket(0).use { it.localPort }
        val flaky = HttpServer.create(InetSocketAddress("127.0.0.1", port), 0)
        val request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port/")).GET().build()
        Thread {
            Thread.sleep(40)
            flaky.createContext("/") { exchange ->
                exchange.sendResponseHeaders(200, -1)
                exchange.close()
            }
            flaky.start()
        }.start()
        try {
            val response =
                breakers.send(
                    Downstream.FORM,
                    HttpClient.newHttpClient(),
                    request,
                    HttpResponse.BodyHandlers.discarding(),
                    idempotent = true,
                )
            response.statusCode() shouldBe 200
            breakers.of(Downstream.FORM).metrics.numberOfFailedCalls shouldBe 0
            breakers.of(Downstream.FORM).metrics.numberOfSuccessfulCalls shouldBe 1
        } finally {
            flaky.stop(0)
        }
    }

    @Test
    fun `5xx 응답은 재시도하지 않고 PUT도 한 번만 보낸다`() {
        status = 500
        assertThrows(ExpectedException::class.java) { admin().requireCurrentAdmin("Bearer t") }
        calls.get() shouldBe 1

        assertThrows(ExpectedException::class.java) { preregister().adoptWaitingCapacity("expo", 5, 3) }
        calls.get() shouldBe 2
        synchronized(methods) { methods shouldBe listOf("GET", "PUT") }
    }

    @Test
    fun `타임아웃은 GET이어도 재시도하지 않는다`() {
        val slow = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val hits = AtomicInteger()
        slow.createContext("/") { exchange ->
            hits.incrementAndGet()
            Thread.sleep(600)
            exchange.sendResponseHeaders(200, -1)
            exchange.close()
        }
        slow.start()
        try {
            val request =
                HttpRequest
                    .newBuilder(URI.create("http://127.0.0.1:${slow.address.port}/"))
                    .timeout(Duration.ofMillis(100))
                    .GET()
                    .build()
            assertThrows(java.net.http.HttpTimeoutException::class.java) {
                breakers.send(
                    Downstream.FORM,
                    HttpClient.newHttpClient(),
                    request,
                    HttpResponse.BodyHandlers.discarding(),
                    idempotent = true,
                )
            }
            hits.get() shouldBe 1
            breakers.of(Downstream.FORM).metrics.numberOfFailedCalls shouldBe 1
        } finally {
            slow.stop(0)
        }
    }
}
