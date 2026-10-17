package team.startup.expo.domain.expo.service

import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import team.startup.expo.domain.expo.presentation.dto.response.ProgramChangePayload
import team.startup.expo.domain.expo.presentation.dto.response.ProgramDefinition
import team.startup.expo.global.exception.ExpectedException
import tools.jackson.databind.JsonNode
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
    @Value("\${APPLICATION_INTERNAL_TOKEN:}") private val applicationToken: String,
    @Value("\${expo.form-service-url:}") private val formUrl: String,
    @Value("\${FORM_INTERNAL_TOKEN:}") private val formToken: String,
    private val mapper: ObjectMapper,
) {
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build()

    fun validateForm(definition: ProgramDefinition) {
        val policy = definition.policy ?: return
        val response = send(formUrl, formToken, "/internal/forms/by-id/${policy.formId}", "GET", "")
        if (response.statusCode() == 404) throw ExpectedException(HttpStatus.NOT_FOUND, "프로그램 폼을 찾지 못 했습니다.")
        if (response.statusCode() != 200) badResponse()
        val form =
            try {
                mapper.readValue(response.body(), ProgramForm::class.java)
            } catch (exception: Exception) {
                badResponse()
            }
        if (form.id != policy.formId || form.expoId != definition.expoId || form.purpose != "PROGRAM" ||
            (
                form.participantType != null &&
                    form.participantType != if (definition.programType.name == "STANDARD") "STANDARD" else "TRAINEE"
            )
        ) {
            throw ExpectedException(HttpStatus.BAD_REQUEST, "프로그램 폼의 행사 또는 참여 유형이 일치하지 않습니다.")
        }
        if (form.startDate.isAfter(policy.applicationStartedAt) || form.endDate.isBefore(policy.applicationEndedAt)) {
            throw ExpectedException(HttpStatus.BAD_REQUEST, "폼 기간이 프로그램 모집 기간을 포함해야 합니다.")
        }
        // 현장 접수도 같은 프로그램 폼으로 받으므로 폼 기간이 현장 접수 기간을 포함해야 한다.
        if (policy.onsiteStartedAt != null &&
            (form.startDate.isAfter(policy.onsiteStartedAt) || form.endDate.isBefore(policy.onsiteEndedAt))
        ) {
            throw ExpectedException(HttpStatus.BAD_REQUEST, "폼 기간이 현장 접수 기간을 포함해야 합니다.")
        }
        policy.guardianPolicy?.let { guardian ->
            guardian.validate()
            if (form.version != guardian.formVersion) {
                throw ExpectedException(HttpStatus.CONFLICT, "보호자 정책의 폼 버전이 변경됐습니다. 문항을 다시 연결해 주세요.")
            }
            val questions =
                form.dynamicForm
                    ?.takeIf { it.isArray }
                    ?.toList()
                    .orEmpty()

            fun question(id: Long): JsonNode? =
                questions.singleOrNull {
                    val actual = it.path("id")
                    actual.isIntegralNumber && actual.canConvertToLong() && actual.asLong() == id
                }
            val school = question(guardian.schoolTypeQuestionId)
            val grade = question(guardian.gradeQuestionId)
            val schoolConditional = school?.path("otherJson")?.path("conditional")
            val conditional = grade?.path("otherJson")?.path("conditional")
            val parentIndex = conditional?.path("parentIndex")
            val parent = parentIndex?.takeIf { it.isIntegralNumber && it.canConvertToInt() }?.asInt()?.let { questions.getOrNull(it) }
            val gradeKeys =
                grade
                    ?.path("jsonData")
                    ?.properties()
                    ?.map { it.key }
                    ?.toSet()
            val triggers =
                conditional?.path("triggerValues")?.takeIf { it.isArray }?.map { it.asString() }
                    ?: conditional?.path("triggerValue")?.takeIf { it.isString }?.let { listOf(it.asString()) }
            if (school == null || grade == null || parent != school || questions.indexOf(school) >= questions.indexOf(grade) ||
                school.path("formType").asString() != "DROPDOWN" || !school.path("requiredStatus").asBoolean() ||
                (schoolConditional != null && !schoolConditional.isMissingNode && !schoolConditional.isNull) ||
                !school.path("jsonData").has(guardian.elementarySchoolValue) ||
                grade.path("formType").asString() != "DROPDOWN" || !grade.path("requiredStatus").asBoolean() ||
                gradeKeys != guardian.gradeValues.keys ||
                (conditional.hasNonNull("triggerValue") && conditional.hasNonNull("triggerValues")) ||
                triggers != listOf(guardian.elementarySchoolValue)
            ) {
                throw ExpectedException(HttpStatus.BAD_REQUEST, "보호자 정책은 초등학생 선택 시 표시되는 필수 학년 선택 문항을 참조해야 합니다.")
            }
            val support =
                send(applicationUrl, applicationToken, "/internal/expos/${definition.expoId}/program-policy-capabilities", "GET", "")
            requireGuardianSupport(support)
        }
    }

    fun prepare(
        expoId: String,
        changeId: UUID,
        payload: ProgramChangePayload,
    ) {
        val guarded = payload.mutations.filter { it.previous != null && (it.previous.policy != null || it.definition?.policy != null) }
        if (guarded.isEmpty()) return
        val guardianChange = guarded.any { it.previous?.policy?.guardianPolicy != null || it.definition?.policy?.guardianPolicy != null }
        val response =
            send(
                applicationUrl,
                applicationToken,
                "/internal/expos/$expoId/program-changes/$changeId${if (guardianChange) "/guardian-policy" else ""}",
                "PUT",
                mapper.writeValueAsString(ProgramChangePayload(guarded)),
            )
        when (response.statusCode()) {
            204 -> {
                if (guardianChange) requireGuardianSupport(response)
            }

            409 -> {
                throw ExpectedException(HttpStatus.CONFLICT, "신청 이력, 활성 인원 또는 진행 중인 변경으로 프로그램을 변경할 수 없습니다.")
            }

            else -> {
                if (guardianChange && response.statusCode() in listOf(404, 405)) requireGuardianSupport(response)
                badResponse()
            }
        }
    }

    // 구버전 Application이 새 정책을 무시한 채 승인하는 것을 막는다.
    private fun requireGuardianSupport(response: HttpResponse<String>) {
        if (response.statusCode() !in listOf(200, 204) ||
            response.headers().firstValue("X-Program-Guardian-Policy-Version").orElse("") != "1"
        ) {
            throw ExpectedException(HttpStatus.SERVICE_UNAVAILABLE, "Application의 보호자 동반 정책 지원 확인이 필요합니다.")
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
        val participantType: String?,
        val startDate: Instant,
        val endDate: Instant,
        val version: Long? = null,
        val dynamicForm: JsonNode? = null,
    )
}
