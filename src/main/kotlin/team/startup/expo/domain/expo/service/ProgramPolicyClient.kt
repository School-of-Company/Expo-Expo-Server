package team.startup.expo.domain.expo.service

import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import team.startup.expo.domain.expo.presentation.dto.response.ProgramChangePayload
import team.startup.expo.domain.expo.presentation.dto.response.ProgramDefinition
import team.startup.expo.global.exception.ExpectedException
import tools.jackson.databind.ObjectMapper
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant
import java.util.UUID

@Component
class ProgramPolicyClient(
    @Value("\${expo.standard.application-service-url:}") private val applicationUrl: String,
    @Value("\${APPLICATION_INTERNAL_TOKEN:\${expo.standard.internal-token:}}") private val applicationToken: String,
    @Value("\${expo.form-service-url:}") private val formUrl: String,
    @Value("\${FORM_INTERNAL_TOKEN:\${expo.form.internal-token:}}") private val formToken: String,
    private val mapper: ObjectMapper,
) {
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build()

    fun validateForm(definition: ProgramDefinition) {
        val policy = definition.policy ?: return
        val response = send(formUrl, formToken, "/internal/program-forms/${policy.formId}", "GET", "")
        if (response.statusCode() == 404) throw ExpectedException(HttpStatus.NOT_FOUND, "프로그램 폼을 찾지 못 했습니다.")
        if (response.statusCode() != 200) badResponse()
        val form =
            try {
                mapper.readValue(response.body(), ProgramForm::class.java)
            } catch (exception: Exception) {
                badResponse()
            }
        if (form.id != policy.formId || form.expoId != definition.expoId || form.purpose != "PROGRAM" ||
            form.participantType != if (definition.programType.name == "STANDARD") "STANDARD" else "TRAINEE"
        ) {
            throw ExpectedException(HttpStatus.BAD_REQUEST, "프로그램 폼의 행사 또는 참여 유형이 일치하지 않습니다.")
        }
        if (form.startDate.isAfter(policy.applicationStartedAt) || form.endDate.isBefore(policy.applicationEndedAt)) {
            throw ExpectedException(HttpStatus.BAD_REQUEST, "폼 기간이 프로그램 모집 기간을 포함해야 합니다.")
        }
    }

    fun prepare(
        expoId: String,
        changeId: UUID,
        payload: ProgramChangePayload,
    ) {
        val guarded = payload.mutations.filter { it.previous != null && (it.previous.policy != null || it.definition?.policy != null) }
        if (guarded.isEmpty()) return
        val response =
            send(
                applicationUrl,
                applicationToken,
                "/internal/expos/$expoId/program-changes/$changeId",
                "PUT",
                mapper.writeValueAsString(ProgramChangePayload(guarded)),
            )
        when (response.statusCode()) {
            204 -> Unit
            409 -> throw ExpectedException(HttpStatus.CONFLICT, "신청 이력, 활성 인원 또는 진행 중인 변경으로 프로그램을 변경할 수 없습니다.")
            else -> badResponse()
        }
    }

    private fun send(
        base: String,
        token: String,
        path: String,
        method: String,
        body: String,
    ): HttpResponse<String> {
        if (base.isBlank() || token.isBlank()) {
            throw ExpectedException(HttpStatus.SERVICE_UNAVAILABLE, "프로그램 정책 내부 연동이 설정되지 않았습니다.")
        }
        try {
            val request =
                HttpRequest
                    .newBuilder(URI.create("${base.trimEnd('/')}$path"))
                    .timeout(Duration.ofSeconds(5))
                    .header("Content-Type", "application/json")
                    .header("X-Internal-Token", token)
                    .method(method, if (body.isEmpty()) HttpRequest.BodyPublishers.noBody() else HttpRequest.BodyPublishers.ofString(body))
                    .build()
            return http.send(request, HttpResponse.BodyHandlers.ofString())
        } catch (exception: InterruptedException) {
            Thread.currentThread().interrupt()
            throw ExpectedException(HttpStatus.SERVICE_UNAVAILABLE, "프로그램 정책 연동이 중단됐습니다.")
        } catch (exception: IOException) {
            throw ExpectedException(HttpStatus.SERVICE_UNAVAILABLE, "프로그램 정책 서비스에 연결할 수 없습니다.")
        }
    }

    private fun badResponse(): Nothing = throw ExpectedException(HttpStatus.BAD_GATEWAY, "프로그램 정책 서비스 응답을 확인할 수 없습니다.")

    private data class ProgramForm(
        val id: UUID,
        val expoId: String,
        val purpose: String,
        val participantType: String,
        val startDate: Instant,
        val endDate: Instant,
    )
}
