package team.startup.expo.domain.expo.service

import feign.Response
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Lazy
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import team.startup.expo.domain.expo.presentation.dto.response.PreregisterSessionResponse
import team.startup.expo.global.client.ApplicationClient
import team.startup.expo.global.client.Downstream
import team.startup.expo.global.client.DownstreamCircuitBreakers
import team.startup.expo.global.client.bodyText
import team.startup.expo.global.exception.ExpectedException
import tools.jackson.databind.ObjectMapper
import java.io.IOException

@Component
class PreregisterSessionChangesClient(
    @Value("\${expo.standard.application-service-url:}") private val applicationUrl: String,
    @Value("\${APPLICATION_INTERNAL_TOKEN:}") private val internalToken: String,
    private val mapper: ObjectMapper,
    @Lazy private val applications: ApplicationClient,
    private val breakers: DownstreamCircuitBreakers,
) {
    fun reconcile(
        expoId: String,
        sessionId: Long,
    ) {
        if (applicationUrl.isBlank() || internalToken.isBlank()) {
            throw ExpectedException(HttpStatus.SERVICE_UNAVAILABLE, "회차 변경 내부 연동이 설정되지 않았습니다.")
        }
        try {
            val response = breakers.call(Downstream.APPLICATION) { applications.reconcile(expoId, sessionId) }
            if (response.status() == 409 || (response.status() == 200 && response.bodyText().trim() == "false")) {
                throw ExpectedException(HttpStatus.CONFLICT, "회차 변경 확인이 완료되지 않았습니다. 변경 재시도를 실행해 주세요.")
            }
            if (response.status() != 200 || response.bodyText().trim() != "true") {
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
            put {
                applications.prepareSession(
                    expoId,
                    sessionId,
                    nextRevision,
                    mapper.writeValueAsString(Change(operation, definitionChanged, definition)),
                )
            }
        when (response.status()) {
            204 -> Unit
            409 -> throw applicationConflict(mapper, response.bodyText(), "신청 이력 또는 진행 중인 변경으로 회차를 변경할 수 없습니다.")
            else -> throw ExpectedException(HttpStatus.BAD_GATEWAY, "신청 서비스 회차 변경 응답을 확인할 수 없습니다.")
        }
    }

    // Application rejects with 409 when expected differs from the Expo value it observes or a change is pending.
    fun adoptWaitingCapacity(
        expoId: String,
        sessionId: Long,
        expected: Int,
    ) {
        when (
            put {
                applications.adoptWaitingCapacity(expoId, sessionId, mapper.writeValueAsString(mapOf("expected" to expected)))
            }.status()
        ) {
            204 -> Unit
            404 -> throw ExpectedException(HttpStatus.NOT_FOUND, "신청 서비스에서 회차를 찾지 못 했습니다.")
            409 -> throw ExpectedException(HttpStatus.CONFLICT, "대기 정원이 Expo 현재 값과 다르거나 회차 변경 확인 중입니다.")
            else -> throw ExpectedException(HttpStatus.BAD_GATEWAY, "신청 서비스 대기 정원 채택 응답을 확인할 수 없습니다.")
        }
    }

    private fun put(block: () -> Response): Response {
        if (applicationUrl.isBlank() || internalToken.isBlank()) {
            throw ExpectedException(HttpStatus.SERVICE_UNAVAILABLE, "회차 변경 내부 연동이 설정되지 않았습니다.")
        }
        try {
            return breakers.call(Downstream.APPLICATION, block = block)
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
