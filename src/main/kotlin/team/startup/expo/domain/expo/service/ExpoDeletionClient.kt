package team.startup.expo.domain.expo.service

import feign.Response
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Lazy
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import team.startup.expo.global.attendance.ProgramAttendanceClient
import team.startup.expo.global.client.ApplicationClient
import team.startup.expo.global.client.Downstream
import team.startup.expo.global.client.DownstreamCircuitBreakers
import team.startup.expo.global.client.FormClient
import team.startup.expo.global.client.StandardUserClient
import team.startup.expo.global.exception.ExpectedException
import tools.jackson.databind.ObjectMapper
import java.io.IOException

@Component
class ExpoDeletionClient(
    @Value("\${expo.standard.application-service-url:}") private val applicationUrl: String,
    @Value("\${expo.form-service-url:}") private val formUrl: String,
    @Value("\${expo.standard.user-service-url:}") private val userUrl: String,
    @Value("\${APPLICATION_INTERNAL_TOKEN:}") private val applicationToken: String,
    @Value("\${FORM_INTERNAL_TOKEN:}") private val formToken: String,
    @Value("\${USER_INTERNAL_TOKEN:}") private val userToken: String,
    private val mapper: ObjectMapper,
    private val attendances: ProgramAttendanceClient,
    @Lazy private val applications: ApplicationClient,
    @Lazy private val forms: FormClient,
    @Lazy private val users: StandardUserClient,
    private val breakers: DownstreamCircuitBreakers,
) {
    fun requireConfiguration() {
        if (applicationUrl.isBlank() || formUrl.isBlank() || userUrl.isBlank() ||
            applicationToken.isBlank() || formToken.isBlank() || userToken.isBlank()
        ) {
            throw ExpectedException(HttpStatus.SERVICE_UNAVAILABLE, "박람회 삭제 내부 연동이 설정되지 않았습니다.")
        }
        attendances.requireConfiguration()
    }

    fun deleteApplications(
        expoId: String,
        standardIds: List<Long>,
        trainingIds: List<Long>,
    ) {
        call(Downstream.APPLICATION, applicationUrl, applicationToken, "Application") {
            applications.purge(expoId, mapper.writeValueAsString(ProgramIds(standardIds, trainingIds)))
        }
    }

    fun deleteForms(expoId: String) = call(Downstream.FORM, formUrl, formToken, "Form") { forms.deleteExpo(expoId) }

    fun deleteUsers(expoId: String) = call(Downstream.USER, userUrl, userToken, "User") { users.deleteExpo(expoId) }

    fun deleteAttendances(
        expoId: String,
        standardIds: List<Long>,
        trainingIds: List<Long>,
    ) {
        standardIds.forEach(attendances::deleteStandard)
        trainingIds.forEach(attendances::deleteTraining)
        attendances.deleteExpo(expoId)
    }

    private fun call(
        downstream: Downstream,
        baseUrl: String,
        token: String,
        service: String,
        block: () -> Response,
    ) {
        if (baseUrl.isBlank() || token.isBlank()) {
            throw ExpectedException(HttpStatus.SERVICE_UNAVAILABLE, "박람회 삭제 내부 연동이 설정되지 않았습니다.")
        }
        try {
            if (breakers.call(downstream, block = block).status() != 204) {
                throw ExpectedException(HttpStatus.BAD_GATEWAY, "$service 서비스 삭제 응답을 확인할 수 없습니다.")
            }
        } catch (exception: ExpectedException) {
            throw exception
        } catch (exception: InterruptedException) {
            Thread.currentThread().interrupt()
            throw ExpectedException(HttpStatus.SERVICE_UNAVAILABLE, "$service 서비스 삭제 요청이 중단됐습니다.")
        } catch (exception: IOException) {
            throw ExpectedException(HttpStatus.SERVICE_UNAVAILABLE, "$service 서비스에 연결할 수 없습니다.")
        }
    }

    private data class ProgramIds(
        val standardProgramIds: List<Long>,
        val trainingProgramIds: List<Long>,
    )
}
