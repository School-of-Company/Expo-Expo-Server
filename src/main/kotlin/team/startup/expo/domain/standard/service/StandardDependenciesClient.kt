package team.startup.expo.domain.standard.service

import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import team.startup.expo.global.exception.ExpectedException
import tools.jackson.databind.ObjectMapper
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

@Component
class StandardDependenciesClient(
    @Value("\${expo.standard.user-service-url:}") private val userServiceUrl: String,
    @Value("\${expo.standard.application-service-url:}") private val applicationServiceUrl: String,
    @Value("\${expo.standard.internal-token:}") private val internalToken: String,
    private val mapper: ObjectMapper,
) {
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build()

    fun resolveParticipant(
        expoId: String,
        phoneNumber: String,
    ): Long {
        val response = post(userServiceUrl, "/internal/standard-participants/resolve", ResolveParticipantRequest(expoId, phoneNumber))
        if (response.statusCode() == 404) throw ExpectedException(HttpStatus.NOT_FOUND, "행사 참가자를 찾지 못 했습니다.")
        if (response.statusCode() != 200) badResponse("User")
        val participantId = decode(response.body(), ResolveParticipantResponse::class.java).participantId
        if (participantId <= 0) badResponse("User")
        return participantId
    }

    fun participantNames(
        expoId: String,
        participantIds: List<Long>,
    ): List<ParticipantName> {
        val response = post(userServiceUrl, "/internal/standard-participants/names", ParticipantNamesRequest(expoId, participantIds))
        if (response.statusCode() != 200) badResponse("User")
        return decode(response.body(), Array<ParticipantName>::class.java).toList()
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
        when (post(applicationServiceUrl, "/internal/standard-program-applications", body).statusCode()) {
            201 -> Unit
            409 -> throw ExpectedException(HttpStatus.CONFLICT, "이미 신청한 유저입니다.")
            else -> badResponse("Application")
        }
    }

    fun applications(programId: Long): List<ProgramApplication> {
        val response = get(applicationServiceUrl, "/internal/standard-program-applications/program/$programId")
        if (response.statusCode() != 200) badResponse("Application")
        return decode(response.body(), Array<ProgramApplication>::class.java).toList()
    }

    fun deleteApplications(programId: Long) {
        if (delete(applicationServiceUrl, "/internal/standard-program-applications/program/$programId").statusCode() != 204) {
            badResponse("Application")
        }
    }

    private fun post(
        baseUrl: String,
        path: String,
        body: Any,
    ): HttpResponse<String> =
        send(
            baseUrl,
            path,
            "POST",
            HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)),
        )

    private fun get(
        baseUrl: String,
        path: String,
    ): HttpResponse<String> = send(baseUrl, path, "GET", HttpRequest.BodyPublishers.noBody())

    private fun delete(
        baseUrl: String,
        path: String,
    ): HttpResponse<String> = send(baseUrl, path, "DELETE", HttpRequest.BodyPublishers.noBody())

    private fun send(
        baseUrl: String,
        path: String,
        method: String,
        body: HttpRequest.BodyPublisher,
    ): HttpResponse<String> {
        if (baseUrl.isBlank() || internalToken.isBlank()) {
            throw ExpectedException(HttpStatus.SERVICE_UNAVAILABLE, "일반 프로그램 내부 연동이 설정되지 않았습니다.")
        }
        try {
            val request =
                HttpRequest
                    .newBuilder(URI.create("${baseUrl.trimEnd('/')}$path"))
                    .timeout(Duration.ofSeconds(5))
                    .header("Content-Type", "application/json")
                    .header("X-Internal-Token", internalToken)
                    .method(method, body)
                    .build()
            return http.send(request, HttpResponse.BodyHandlers.ofString())
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
