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
    @Value("\${APPLICATION_INTERNAL_TOKEN:}") private val internalToken: String,
    private val mapper: ObjectMapper,
) {
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build()

    fun reconcile(
        expoId: String,
        sessionId: Long,
    ) {
        if (applicationUrl.isBlank() || internalToken.isBlank()) {
            throw ExpectedException(HttpStatus.SERVICE_UNAVAILABLE, "회차 변경 내부 연동이 설정되지 않았습니다.")
        }
        try {
            val request =
                HttpRequest
                    .newBuilder(
                        URI.create("${applicationUrl.trimEnd('/')}/internal/expos/$expoId/preregister-sessions/$sessionId/reconciliation"),
                    ).timeout(Duration.ofSeconds(5))
                    .header("X-Internal-Token", internalToken)
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .build()
            val response = http.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() == 409 || (response.statusCode() == 200 && response.body().trim() == "false")) {
                throw ExpectedException(HttpStatus.CONFLICT, "회차 변경 확인이 완료되지 않았습니다. 변경 재시도를 실행해 주세요.")
            }
            if (response.statusCode() != 200 || response.body().trim() != "true") {
                throw ExpectedException(HttpStatus.BAD_GATEWAY, "신청 서비스 회차 변경 확인 응답이 올바르지 않습니다.")
            }
        } catch (exception: InterruptedException) {
            Thread.currentThread().interrupt()
            throw ExpectedException(HttpStatus.SERVICE_UNAVAILABLE, "회차 변경 확인이 중단됐습니다.")
        } catch (exception: IOException) {
            throw ExpectedException(HttpStatus.SERVICE_UNAVAILABLE, "신청 서비스에 연결할 수 없습니다.")
        }
    }

    // Application must persist the admission block before acknowledging; a history lookup alone is unsafe.
    fun prepare(
        expoId: String,
        sessionId: Long,
        nextRevision: Long,
        operation: String,
        definitionChanged: Boolean,
        definition: PreregisterSessionResponse?,
    ) {
        val response =
            put("$expoId/preregister-sessions/$sessionId/changes/$nextRevision", Change(operation, definitionChanged, definition))
        when (response.statusCode()) {
            204 -> Unit
            409 -> throw applicationConflict(mapper, response.body(), "신청 이력 또는 진행 중인 변경으로 회차를 변경할 수 없습니다.")
            else -> throw ExpectedException(HttpStatus.BAD_GATEWAY, "신청 서비스 회차 변경 응답을 확인할 수 없습니다.")
        }
    }

    // Application rejects with 409 when expected differs from the Expo value it observes or a change is pending.
    fun adoptWaitingCapacity(
        expoId: String,
        sessionId: Long,
        expected: Int,
    ) {
        when (put("$expoId/preregister-sessions/$sessionId/waiting-capacity", mapOf("expected" to expected)).statusCode()) {
            204 -> Unit
            404 -> throw ExpectedException(HttpStatus.NOT_FOUND, "신청 서비스에서 회차를 찾지 못 했습니다.")
            409 -> throw ExpectedException(HttpStatus.CONFLICT, "대기 정원이 Expo 현재 값과 다르거나 회차 변경 확인 중입니다.")
            else -> throw ExpectedException(HttpStatus.BAD_GATEWAY, "신청 서비스 대기 정원 채택 응답을 확인할 수 없습니다.")
        }
    }

    private fun put(
        path: String,
        body: Any,
    ): HttpResponse<String> {
        if (applicationUrl.isBlank() || internalToken.isBlank()) {
            throw ExpectedException(HttpStatus.SERVICE_UNAVAILABLE, "회차 변경 내부 연동이 설정되지 않았습니다.")
        }
        try {
            val request =
                HttpRequest
                    .newBuilder(URI.create("${applicationUrl.trimEnd('/')}/internal/expos/$path"))
                    .timeout(Duration.ofSeconds(5))
                    .header("Content-Type", "application/json")
                    .header("X-Internal-Token", internalToken)
                    .PUT(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                    .build()
            return http.send(request, HttpResponse.BodyHandlers.ofString())
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
