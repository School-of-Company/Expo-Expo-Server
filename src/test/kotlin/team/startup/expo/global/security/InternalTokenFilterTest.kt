package team.startup.expo.global.security

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockFilterChain
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import tools.jackson.databind.ObjectMapper

class InternalTokenFilterTest {
    @Test
    fun `토큰 설정이 없으면 헤더가 있어도 내부 조회를 거부한다`() {
        val request =
            MockHttpServletRequest("GET", "/internal/expo/expo-id").apply {
                servletPath = "/internal/expo/expo-id"
                addHeader("X-Internal-Token", "supplied-token")
            }
        val response = MockHttpServletResponse()
        val chain = MockFilterChain()

        InternalTokenFilter("", ObjectMapper()).doFilter(request, response, chain)

        response.status shouldBe 401
        chain.request shouldBe null
    }
}
