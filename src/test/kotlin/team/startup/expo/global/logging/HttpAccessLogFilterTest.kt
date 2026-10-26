package team.startup.expo.global.logging

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldMatch
import io.kotest.matchers.string.shouldNotContain
import jakarta.servlet.FilterChain
import org.apache.catalina.connector.ClientAbortException
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.web.servlet.HandlerMapping

class HttpAccessLogFilterTest {
    private val logger = LoggerFactory.getLogger(HttpAccessLogFilter.LOGGER_NAME) as Logger
    private val appender = ListAppender<ILoggingEvent>()

    @BeforeEach
    fun attach() {
        appender.start()
        logger.addAppender(appender)
    }

    @AfterEach
    fun detach() {
        logger.detachAppender(appender)
        appender.stop()
    }

    @Test
    fun `라우트 템플릿과 상태 코드로 한 번만 남기고 module은 HttpAccess다`() {
        run(path = "/expo/abc123", status = 200, pattern = "/expo/{expo_id}")

        appender.list.size shouldBe 1
        appender.list[0].level shouldBe Level.INFO
        appender.list[0].formattedMessage shouldMatch Regex("""GET /expo/\{expo_id} status: 200 \(\d+ms\)""")
        ModuleNameConverter.moduleOf(appender.list[0].loggerName) shouldBe "HttpAccess"
    }

    @Test
    fun `4xx는 WARN, 5xx는 ERROR로 남긴다`() {
        run(status = 401)
        run(status = 404)
        run(status = 503)

        appender.list.map { it.level } shouldBe listOf(Level.WARN, Level.WARN, Level.ERROR)
    }

    @Test
    fun `템플릿이 없으면 쿼리스트링 없이 원본 경로를 200자까지 남긴다`() {
        val longPath = "/expo/" + "a".repeat(300)
        run(path = longPath, status = 404, query = "token=secret&phone=01012345678")

        val message = appender.list.single().formattedMessage
        message shouldNotContain "secret"
        message shouldNotContain "?"
        message shouldMatch Regex("""GET ${longPath.take(200)} status: 404 \(\d+ms\)""")
    }

    @Test
    fun `클라이언트가 끊으면 499를 ERROR로 한 번만 남긴다`() {
        shouldThrow<ClientAbortException> {
            run(status = 200, chainFailure = ClientAbortException("Broken pipe"))
        }

        appender.list.size shouldBe 1
        appender.list[0].level shouldBe Level.ERROR
        appender.list[0].formattedMessage shouldMatch Regex("""GET /expo status: 499 \(\d+ms\)""")
    }

    @Test
    fun `actuator와 Swagger 경로는 남기지 않는다`() {
        for (path in listOf(
            "/actuator/health",
            "/actuator/prometheus",
            "/expo/v3/api-docs",
            "/expo/swagger-ui.html",
            "/expo/swagger-ui/index.html",
        )) {
            run(path = path, status = 200)
        }

        appender.list.size shouldBe 0
    }

    @Test
    fun `logging_http-access_enabled가 false면 필터를 등록하지 않는다`() {
        val runner = ApplicationContextRunner().withUserConfiguration(HttpAccessLogConfig::class.java)

        runner.run { it.getBeansOfType(FilterRegistrationBean::class.java).size shouldBe 1 }
        runner.withPropertyValues("logging.http-access.enabled=false").run {
            it.getBeansOfType(FilterRegistrationBean::class.java).size shouldBe 0
        }
    }

    private fun run(
        path: String = "/expo",
        status: Int,
        pattern: String? = null,
        query: String? = null,
        chainFailure: Exception? = null,
    ) {
        val request = MockHttpServletRequest("GET", path).apply { queryString = query }
        val response = MockHttpServletResponse()
        val chain =
            FilterChain { req, res ->
                pattern?.let { req.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, it) }
                (res as MockHttpServletResponse).status = status
                chainFailure?.let { throw it }
            }

        HttpAccessLogFilter().doFilter(request, response, chain)
    }
}
