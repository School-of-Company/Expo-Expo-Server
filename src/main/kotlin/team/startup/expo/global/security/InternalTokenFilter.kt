package team.startup.expo.global.security

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.MediaType
import org.springframework.web.filter.OncePerRequestFilter
import team.startup.expo.global.exception.ErrorResponse
import tools.jackson.databind.ObjectMapper
import java.security.MessageDigest

class InternalTokenFilter(
    expectedToken: String,
    private val objectMapper: ObjectMapper,
) : OncePerRequestFilter() {
    private val expectedTokenBytes = expectedToken.toByteArray(Charsets.UTF_8)

    override fun shouldNotFilter(request: HttpServletRequest): Boolean =
        request.method != "GET" || !INTERNAL_EXPO_PATH.matches(request.servletPath)

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val suppliedToken = request.getHeader(INTERNAL_TOKEN_HEADER)
        if (
            expectedTokenBytes.isEmpty() ||
            suppliedToken.isNullOrBlank() ||
            !MessageDigest.isEqual(expectedTokenBytes, suppliedToken.toByteArray(Charsets.UTF_8))
        ) {
            response.status = HttpServletResponse.SC_UNAUTHORIZED
            response.characterEncoding = Charsets.UTF_8.name()
            response.contentType = MediaType.APPLICATION_JSON_VALUE
            objectMapper.writeValue(response.writer, ErrorResponse(status = response.status, message = "인증이 필요합니다."))
            return
        }

        filterChain.doFilter(request, response)
    }

    private companion object {
        const val INTERNAL_TOKEN_HEADER = "X-Internal-Token"
        val INTERNAL_EXPO_PATH = Regex("^/internal/expo/[^/]+$")
    }
}
