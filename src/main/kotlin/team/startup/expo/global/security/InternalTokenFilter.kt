package team.startup.expo.global.security

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.filter.OncePerRequestFilter
import tools.jackson.databind.ObjectMapper
import java.security.MessageDigest

class InternalTokenFilter(
    expectedToken: String,
    private val objectMapper: ObjectMapper,
) : OncePerRequestFilter() {
    private val expectedTokenBytes = expectedToken.toByteArray(Charsets.UTF_8)

    override fun shouldNotFilter(request: HttpServletRequest): Boolean = !request.servletPath.startsWith(ApiPaths.INTERNAL_PREFIX)

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
            SecurityErrorSupport.write(objectMapper, response, HttpServletResponse.SC_UNAUTHORIZED, "인증이 필요합니다.")
            return
        }

        val context = SecurityContextHolder.createEmptyContext()
        context.authentication =
            UsernamePasswordAuthenticationToken.authenticated(
                "internal-service",
                null,
                listOf(SimpleGrantedAuthority(INTERNAL_AUTHORITY)),
            )
        SecurityContextHolder.setContext(context)
        filterChain.doFilter(request, response)
    }

    companion object {
        const val INTERNAL_AUTHORITY = "ROLE_INTERNAL_SERVICE"
        private const val INTERNAL_TOKEN_HEADER = "X-Internal-Token"
    }
}
