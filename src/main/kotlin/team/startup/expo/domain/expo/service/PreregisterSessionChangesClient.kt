package team.startup.expo.domain.expo.service

import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import team.startup.expo.domain.expo.presentation.dto.response.PreregisterSessionResponse
import team.startup.expo.global.exception.ExpectedException
import tools.jackson.databind.ObjectMapper
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

@Component
class PreregisterSessionChangesClient(
    @Value("\${expo.standard.application-service-url:}") private val applicationUrl: String,
    @Value("\${expo.standard.internal-token:}") private val internalToken: String,
    private val mapper: ObjectMapper,
) {
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build()

    // Application must persist the admission block before acknowledging; a history lookup alone is unsafe.
    fun prepare(
        expoId: String,
        sessionId: Long,
        nextRevision: Long,
        operation: String,
        definitionChanged: Boolean,
        definition: PreregisterSessionResponse?,
    ) {
        if (applicationUrl.isBlank() || internalToken.isBlank()) {
            throw ExpectedException(HttpStatus.SERVICE_UNAVAILABLE, "회차 변경 내부 연동이 설정되지 않았습니다.")
        }
        try {
            val request =
                HttpRequest
                    .newBuilder(
                        URI.create(
                            "${applicationUrl.trimEnd('/')}/internal/expos/$expoId/preregister-sessions/$sessionId/changes/$nextRevision",
                        ),
                    ).timeout(Duration.ofSeconds(5))
                    .header("Content-Type", "application/json")
                    .header("X-Internal-Token", internalToken)
                    .PUT(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(Change(operation, definitionChanged, definition))))
                    .build()
            when (http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode()) {
                204 -> Unit
                409 -> throw ExpectedException(HttpStatus.CONFLICT, "신청 이력 또는 진행 중인 변경으로 회차를 변경할 수 없습니다.")
                else -> throw ExpectedException(HttpStatus.BAD_GATEWAY, "신청 서비스 회차 변경 응답을 확인할 수 없습니다.")
            }
        } catch (exception: ExpectedException) {
            throw exception
        } catch (exception: InterruptedException) {
            Thread.currentThread().interrupt()
            throw ExpectedException(HttpStatus.SERVICE_UNAVAILABLE, "회차 변경 요청이 중단됐습니다.")
        } catch (exception: IOException) {
            throw ExpectedException(HttpStatus.SERVICE_UNAVAILABLE, "신청 서비스에 연결할 수 없습니다.")
        }
    }

    private data class Change(
        val operation: String,
        val definitionChanged: Boolean,
        val definition: PreregisterSessionResponse?,
    )
}
