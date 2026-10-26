package team.startup.expo.global.logging

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.web.filter.OncePerRequestFilter
import org.springframework.web.servlet.HandlerMapping
import org.springframework.web.util.DisconnectedClientHelper

/**
 * 요청마다 접근 로그 한 줄(`GET /expo/{expo_id} status: 200 (12ms)`)을 남긴다. 모든 Expo 서비스가 같은 형식을 쓴다.
 * Security 필터보다 앞에 두어 401·403 거절도 남긴다. 쿼리스트링·헤더·쿠키·본문은 남기지 않는다.
 * 로거 이름이 `HttpAccess`라서 JSON `module`도 `HttpAccess`가 된다(`ModuleNameConverter`).
 */
class HttpAccessLogFilter : OncePerRequestFilter() {
    override fun shouldNotFilter(request: HttpServletRequest): Boolean {
        val path = request.requestURI.removePrefix(request.contextPath)
        return SKIPPED_PATH_PREFIXES.any { path == it || path.startsWith("$it/") || path.startsWith("$it.") }
    }

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val startedAt = System.nanoTime()
        var failure: Throwable? = null
        try {
            filterChain.doFilter(request, response)
        } catch (e: Throwable) {
            failure = e
            throw e
        } finally {
            val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000
            val status =
                when {
                    failure != null && DisconnectedClientHelper.isClientDisconnectedException(failure) -> CLIENT_CLOSED_REQUEST
                    failure != null && response.status < 500 -> HttpServletResponse.SC_INTERNAL_SERVER_ERROR
                    else -> response.status
                }
            write(status, "${request.method} ${pathOf(request)} status: $status (${elapsedMs}ms)")
        }
    }

    private fun write(
        status: Int,
        message: String,
    ) {
        when {
            status >= 500 || status == CLIENT_CLOSED_REQUEST -> log.error(message)
            status >= 400 -> log.warn(message)
            else -> log.info(message)
        }
    }

    private fun pathOf(request: HttpServletRequest): String =
        (request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE) as? String)
            ?: request.requestURI.take(MAX_RAW_PATH_LENGTH)

    companion object {
        const val LOGGER_NAME = "HttpAccess"
        private const val CLIENT_CLOSED_REQUEST = 499
        private const val MAX_RAW_PATH_LENGTH = 200
        private val SKIPPED_PATH_PREFIXES =
            listOf("/actuator", "/expo/v3/api-docs", "/expo/swagger-ui", "/v3/api-docs", "/swagger-ui")
        private val log: Logger = LoggerFactory.getLogger(LOGGER_NAME)
    }
}
