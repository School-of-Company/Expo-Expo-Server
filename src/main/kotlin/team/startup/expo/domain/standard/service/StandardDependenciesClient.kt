package team.startup.expo.domain.standard.service

import feign.Response
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Lazy
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import team.startup.expo.global.attendance.ProgramAttendanceClient
import team.startup.expo.global.client.ApplicationClient
import team.startup.expo.global.client.Downstream
import team.startup.expo.global.client.DownstreamCircuitBreakers
import team.startup.expo.global.client.StandardUserClient
import team.startup.expo.global.client.bodyText
import team.startup.expo.global.exception.ExpectedException
import tools.jackson.databind.ObjectMapper
import java.io.IOException

@Component
class StandardDependenciesClient(
    @Value("\${expo.standard.user-service-url:}") private val userServiceUrl: String,
    @Value("\${expo.standard.application-service-url:}") private val applicationServiceUrl: String,
    @Value("\${USER_INTERNAL_TOKEN:}") private val userToken: String,
    @Value("\${APPLICATION_INTERNAL_TOKEN:}") private val applicationToken: String,
    private val mapper: ObjectMapper,
    private val attendances: ProgramAttendanceClient,
    @Lazy private val users: StandardUserClient,
    @Lazy private val applications: ApplicationClient,
    private val breakers: DownstreamCircuitBreakers,
) {
    fun resolveParticipant(
        expoId: String,
        phoneNumber: String,
    ): Long {
        val response =
            call(Downstream.USER, userServiceUrl, userToken) {
                users.resolve(mapper.writeValueAsString(ResolveParticipantRequest(expoId, phoneNumber)))
            }
        if (response.status() == 404) throw ExpectedException(HttpStatus.NOT_FOUND, "행사 참가자를 찾지 못 했습니다.")
        if (response.status() != 200) badResponse("User")
        val participantId = decode(response.bodyText(), ResolveParticipantResponse::class.java).participantId
        if (participantId <= 0) badResponse("User")
        return participantId
    }

    fun participantNames(
        expoId: String,
        participantIds: List<Long>,
    ): List<ParticipantName> {
        val response =
            call(Downstream.USER, userServiceUrl, userToken) {
                users.names(mapper.writeValueAsString(ParticipantNamesRequest(expoId, participantIds)))
            }
        if (response.status() != 200) badResponse("User")
        return decode(response.bodyText(), Array<ParticipantName>::class.java).toList()
    }

    fun apply(
        expoId: String,
        participantId: Long,
        programIds: List<Long>,
    ) {
        val body =
            ApplyProgramsCommand(
                participant = ParticipantReference(participantId, expoId),
                programs = programIds.map { ProgramReference(it, expoId) },
            )
        when (
            call(Downstream.APPLICATION, applicationServiceUrl, applicationToken) {
                applications.applyStandard(mapper.writeValueAsString(body))
            }.status()
        ) {
            201 -> Unit
            400 -> throw ExpectedException(HttpStatus.BAD_REQUEST, "일반 프로그램 신청 정보가 올바르지 않습니다.")
            404 -> throw ExpectedException(HttpStatus.NOT_FOUND, "일반 프로그램을 찾지 못 했습니다.")
            409 -> throw ExpectedException(HttpStatus.CONFLICT, "이미 신청한 유저입니다.")
            else -> badResponse("Application")
        }
    }

    fun applications(programId: Long): List<ProgramApplication> {
        val response =
            call(Downstream.APPLICATION, applicationServiceUrl, applicationToken, idempotent = true) {
                applications.standardApplications(programId)
            }
        if (response.status() != 200) badResponse("Application")
        return decode(response.bodyText(), Array<ProgramApplication>::class.java).toList()
    }

    fun deleteApplications(programId: Long) {
        attendances.requireConfiguration()
        if (call(Downstream.APPLICATION, applicationServiceUrl, applicationToken) {
                applications.deleteStandardApplications(programId)
            }.status() !=
            204
        ) {
            badResponse("Application")
        }
        attendances.deleteStandard(programId)
    }

    private fun call(
        downstream: Downstream,
        baseUrl: String,
        token: String,
        idempotent: Boolean = false,
        block: () -> Response,
    ): Response {
        if (baseUrl.isBlank() || token.isBlank()) {
            throw ExpectedException(HttpStatus.SERVICE_UNAVAILABLE, "일반 프로그램 내부 연동이 설정되지 않았습니다.")
        }
        try {
            return breakers.call(downstream, idempotent, block)
        } catch (exception: InterruptedException) {
            Thread.currentThread().interrupt()
            throw ExpectedException(HttpStatus.SERVICE_UNAVAILABLE, "일반 프로그램 내부 연동이 중단됐습니다.")
        } catch (exception: IOException) {
            throw ExpectedException(HttpStatus.SERVICE_UNAVAILABLE, "일반 프로그램 내부 서비스에 연결할 수 없습니다.")
        }
    }

    private fun <T> decode(
        body: String,
        type: Class<T>,
    ): T =
        try {
            mapper.readValue(body, type)
        } catch (exception: Exception) {
            throw ExpectedException(HttpStatus.BAD_GATEWAY, "내부 서비스 응답 형식이 올바르지 않습니다.")
        }

    private fun badResponse(service: String): Nothing = throw ExpectedException(HttpStatus.BAD_GATEWAY, "$service 서비스 응답을 확인할 수 없습니다.")

    private data class ResolveParticipantRequest(
        val expoId: String,
        val phoneNumber: String,
    )

    private data class ResolveParticipantResponse(
        val participantId: Long,
    )

    private data class ParticipantNamesRequest(
        val expoId: String,
        val participantIds: List<Long>,
    )

    data class ParticipantName(
        val participantId: Long,
        val name: String,
    )

    private data class ParticipantReference(
        val id: Long,
        val expoId: String,
    )

    private data class ProgramReference(
        val id: Long,
        val expoId: String,
    )

    private data class ApplyProgramsCommand(
        val participant: ParticipantReference,
        val programs: List<ProgramReference>,
    )

    data class ProgramApplication(
        val applicationId: Long,
        val participantId: Long,
    )
}
