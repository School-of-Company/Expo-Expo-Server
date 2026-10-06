package team.startup.expo.domain.training.service

import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import team.startup.expo.domain.training.entity.Category
import team.startup.expo.global.exception.ExpectedException
import tools.jackson.databind.ObjectMapper
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

@Component
class TrainingDependenciesClient(
    @Value("\${expo.training.user-service-url:}") private val userServiceUrl: String,
    @Value("\${expo.training.application-service-url:}") private val applicationServiceUrl: String,
    @Value("\${expo.training.internal-token:}") private val internalToken: String,
    private val mapper: ObjectMapper,
) {
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build()

    fun resolveTrainee(
        expoId: String,
        trainingId: String,
    ): Long {
        val response = post(userServiceUrl, "/internal/trainees/resolve", ResolveTraineeRequest(expoId, trainingId))
        if (response.statusCode() == 404) throw ExpectedException(HttpStatus.NOT_FOUND, "연수자를 찾지 못 했습니다.")
        if (response.statusCode() == 409) throw ExpectedException(HttpStatus.CONFLICT, "연수자 식별자가 중복됩니다.")
        if (response.statusCode() != 200) badResponse("User")
        val traineeId = decode(response.body(), ResolveTraineeResponse::class.java).traineeId
        if (traineeId <= 0) badResponse("User")
        return traineeId
    }

    fun traineeNames(
        expoId: String,
        traineeIds: List<Long>,
    ): List<TraineeName> {
        val response = post(userServiceUrl, "/internal/trainees/names", TraineeNamesRequest(expoId, traineeIds))
        if (response.statusCode() != 200) badResponse("User")
        return decode(response.body(), Array<TraineeName>::class.java).toList()
    }

    fun apply(
        expoId: String,
        traineeId: Long,
        programId: Long,
        category: Category,
    ) {
        val body =
            ApplyProgramsCommand(
                trainee = TraineeReference(traineeId, expoId),
                programs = listOf(ProgramReference(programId, expoId, category)),
            )
        when (post(applicationServiceUrl, "/internal/training-program-applications", body).statusCode()) {
            201 -> Unit
            409 -> throw ExpectedException(HttpStatus.CONFLICT, "이미 신청했거나 연수 프로그램의 정원이 찼습니다.")
            else -> badResponse("Application")
        }
    }

    fun applications(programId: Long): List<ProgramApplication> {
        val response = get(applicationServiceUrl, "/internal/training-program-applications/program/$programId")
        if (response.statusCode() != 200) badResponse("Application")
        return decode(response.body(), Array<ProgramApplication>::class.java).toList()
    }

    fun deleteApplications(programId: Long) {
        if (delete(applicationServiceUrl, "/internal/training-program-applications/program/$programId").statusCode() != 204) {
            badResponse("Application")
        }
    }

    private fun post(
        baseUrl: String,
        path: String,
        body: Any,
    ): HttpResponse<String> = send(baseUrl, path, "POST", HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))

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
            throw ExpectedException(HttpStatus.SERVICE_UNAVAILABLE, "연수 프로그램 내부 연동이 설정되지 않았습니다.")
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
            throw ExpectedException(HttpStatus.SERVICE_UNAVAILABLE, "연수 프로그램 내부 연동이 중단됐습니다.")
        } catch (exception: IOException) {
            throw ExpectedException(HttpStatus.SERVICE_UNAVAILABLE, "연수 프로그램 내부 서비스에 연결할 수 없습니다.")
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

    private data class ResolveTraineeRequest(
        val expoId: String,
        val trainingId: String,
    )

    private data class ResolveTraineeResponse(
        val traineeId: Long,
    )

    private data class TraineeNamesRequest(
        val expoId: String,
        val traineeIds: List<Long>,
    )

    data class TraineeName(
        val traineeId: Long,
        val name: String,
    )

    private data class TraineeReference(
        val id: Long,
        val expoId: String,
    )

    private data class ProgramReference(
        val id: Long,
        val expoId: String,
        val category: Category,
    )

    private data class ApplyProgramsCommand(
        val trainee: TraineeReference,
        val programs: List<ProgramReference>,
    )

    data class ProgramApplication(
        val applicationId: Long,
        val traineeId: Long,
        val status: Boolean,
        val entryTime: String?,
        val leaveTime: String?,
    )
}
