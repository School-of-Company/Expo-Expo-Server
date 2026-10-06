package team.startup.expo.global.security

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockFilterChain
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.core.context.SecurityContextHolder
import tools.jackson.databind.ObjectMapper

class InternalTokenFilterTest {
    @AfterEach
    fun clearSecurityContext() = SecurityContextHolder.clearContext()

    @Test
    fun `토큰 설정이 없으면 헤더가 있어도 내부 조회를 거부한다`() {
        val (response, chain) = filter(expectedToken = "", suppliedToken = "supplied-token")

        response.status shouldBe 401
        chain.request shouldBe null
    }

    @Test
    fun `올바른 토큰은 내부 서비스 권한을 부여한다`() {
        val (response, chain) = filter(suppliedToken = "expected-token")

        response.status shouldBe 200
        (chain.request != null) shouldBe true
        SecurityContextHolder
            .getContext()
            .authentication
            ?.authorities
            ?.map { it.authority } shouldBe
            listOf(InternalTokenFilter.INTERNAL_AUTHORITY)
    }

    @Test
    fun `토큰 누락과 빈 값 및 불일치는 거부한다`() {
        for (token in listOf(null, "", "wrong-token")) {
            val (response, chain) = filter(suppliedToken = token)

            response.status shouldBe 401
            chain.request shouldBe null
        }
    }

    @Test
    fun `내부 경로의 슬래시와 POST도 토큰을 검사하고 외부 경로는 건너뛴다`() {
        for ((method, path) in listOf("GET" to "/internal/expo/expo-id/", "POST" to "/internal/expo/expo-id")) {
            val (response, chain) = filter(method = method, path = path)
            response.status shouldBe 401
            chain.request shouldBe null
        }

        val (response, chain) = filter(path = "/expo/expo-id")
        response.status shouldBe 200
        (chain.request != null) shouldBe true
    }

    private fun filter(
        expectedToken: String = "expected-token",
        suppliedToken: String? = null,
        method: String = "GET",
        path: String = "/internal/expo/expo-id",
    ): Pair<MockHttpServletResponse, MockFilterChain> {
        val request = MockHttpServletRequest(method, path).apply { servletPath = path }
        suppliedToken?.let { request.addHeader("X-Internal-Token", it) }
        val response = MockHttpServletResponse()
        val chain = MockFilterChain()

        InternalTokenFilter(expectedToken, ObjectMapper()).doFilter(request, response, chain)

        return response to chain
    }
}
