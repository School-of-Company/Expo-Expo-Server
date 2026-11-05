package team.startup.expo.global.security

import org.springframework.util.AntPathMatcher

object ApiPaths {
    const val INTERNAL_PREFIX = "/internal/"
    const val API_DOCS = "/expo/v3/api-docs"
    const val SWAGGER_UI = "/expo/swagger-ui"
    val swaggerPaths = arrayOf(API_DOCS, "$API_DOCS/**", "$SWAGGER_UI.html", "$SWAGGER_UI/**")
    val swaggerLogPrefixes = listOf(API_DOCS, SWAGGER_UI, "/v3/api-docs", "/swagger-ui")
    val healthGetPaths = arrayOf("/actuator/health", "/actuator/health/**", "/actuator/prometheus")
    val publicGetPaths = arrayOf("/image/{id}", "/standard/program/{expo_id}", "/training/program/{expo_id}")
    val publicPostPaths =
        arrayOf(
            "/standard/application/{expo_id}",
            "/training/application/{trainingPro_id}",
            "/training/application/list",
            "/training/application/list/trainee/{expo_id}",
        )
    val publicExpoGetPaths = arrayOf("/expo/{expo_id}/preregister-sessions/public", "/expo/{expo_id}/public")
    private val matcher = AntPathMatcher()

    // 기존 문서는 공개 Expo 조회에도 관리자 인증을 표시한다. #247의 문서 보존 범위에 맞춰 유지한다.
    fun isPublicInDocumentation(
        method: String,
        path: String,
    ): Boolean =
        when (method) {
            "GET" -> publicGetPaths.any { matcher.match(it, path) }
            "POST" -> publicPostPaths.any { matcher.match(it, path) }
            else -> false
        }
}
