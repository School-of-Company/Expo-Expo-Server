package team.startup.expo.domain.expo.service

import org.springframework.http.HttpStatus
import team.startup.expo.global.exception.ExpectedException
import tools.jackson.databind.ObjectMapper

private val REASON_CODE = Regex("[A-Z][A-Z0-9_]{0,63}")

// Application 409 본문에 사유 코드가 있으면 코드와 문구를 관리자에게 전달하고, 없으면 구버전 응답으로 보고 fallback 문구를 쓴다(#188).
internal fun applicationConflict(
    mapper: ObjectMapper,
    body: String,
    fallback: String,
): ExpectedException {
    val error = runCatching { mapper.readTree(body) }.getOrNull()
    val code =
        error
            ?.path("code")
            ?.takeIf { it.isString }
            ?.asString()
            ?.takeIf { REASON_CODE.matches(it) }
            ?: return ExpectedException(HttpStatus.CONFLICT, fallback)
    val message =
        error
            .path("message")
            .takeIf { it.isString }
            ?.asString()
            ?.takeIf { it.isNotBlank() } ?: fallback
    return ExpectedException(HttpStatus.CONFLICT, message, code)
}
