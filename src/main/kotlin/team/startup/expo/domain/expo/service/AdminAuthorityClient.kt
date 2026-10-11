package team.startup.expo.domain.expo.service

import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import team.startup.expo.global.exception.ExpectedException
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

@Component
class AdminAuthorityClient(
    @Value("\${expo.standard.user-service-url:}") private val userServiceUrl: String,
) {
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build()

    fun requireCurrentAdmin(authorization: String) {
        if (userServiceUrl.isBlank()) unavailable()
        try {
            val request =
                HttpRequest
                    .newBuilder(URI.create("${userServiceUrl.trimEnd('/')}/admin/my"))
                    .timeout(Duration.ofSeconds(5))
                    .header("Authorization", authorization)
                    .GET()
                    .build()
            // User checks its current Admin DB before this handler; discard personal information.
            when (http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode()) {
                200 -> Unit
                401 -> throw ExpectedException(HttpStatus.UNAUTHORIZED, "관리자 인증이 유효하지 않습니다.")
                403 -> throw ExpectedException(HttpStatus.FORBIDDEN, "접근 권한이 없습니다.")
                else -> unavailable()
            }
        } catch (exception: IOException) {
            unavailable()
        } catch (exception: InterruptedException) {
            Thread.currentThread().interrupt()
            unavailable()
        }
    }

    private fun unavailable(): Nothing = throw ExpectedException(HttpStatus.SERVICE_UNAVAILABLE, "현재 관리자 권한을 확인할 수 없습니다.")
}
