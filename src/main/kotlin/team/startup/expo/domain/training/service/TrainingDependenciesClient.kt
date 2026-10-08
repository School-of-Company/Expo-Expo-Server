package team.startup.expo.domain.training.service

import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import team.startup.expo.domain.training.presentation.dto.request.ApplyTrainingProgramsWithTraineeRequest
import team.startup.expo.global.attendance.ProgramAttendanceClient
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
class TrainingDependenciesClient(
    @Value("\${expo.training.user-service-url:}") private val userServiceUrl: String,
    @Value("\${expo.training.application-service-url:}") private val applicationServiceUrl: String,
    @Value("\${expo.training.internal-token:}") private val internalToken: String,
    @Value("\${expo.form-service-url:}") private val formServiceUrl: String,
    private val mapper: ObjectMapper,
    private val attendances: ProgramAttendanceClient,
) {
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build()

    fun requireApplicationConfiguration() {
        if (applicationServiceUrl.isBlank() || internalToken.isBlank()) {
            throw ExpectedException(HttpStatus.SERVICE_UNAVAILABLE, "연수 프로그램 내부 연동이 설정되지 않았습니다.")
        }
    }

    fun traineePhoneNumber(
        expoId: String,
        traineeId: Long,
    ): String {
        val response = get(userServiceUrl, "/internal/expos/$expoId/trainees/details?cursor=${traineeId - 1}&size=1")
        if (response.statusCode() != 200) badResponse("User")
        return try {
            val items = mapper.readTree(response.body()).path("items")
            require(items.isArray && items.size() == 1)
            val id = items[0].path("traineeId")
            val phone = items[0].path("phoneNumber")
            require(id.isIntegralNumber && id.canConvertToLong() && id.asLong() == traineeId && phone.isString)
            val stored = phone.asString()
            require(stored.matches(Regex("[0-9 -]+")))
            val digits = stored.replace("-", "").replace(" ", "")
            require(digits.matches(Regex("01[0-9]{8,9}")))
            digits
        } catch (exception: Exception) {
            throw ExpectedException(HttpStatus.BAD_GATEWAY, "연수 문자 수신자를 확인할 수 없습니다.")
        }
    }

    fun resolveTrainee(
        expoId: String,
        trainingId: String,
    ): Long {
        val response = post(userServiceUrl, "/internal/trainees/resolve", ResolveTraineeRequest(expoId, trainingId))
        if (response.statusCode() == 400) throw ExpectedException(HttpStatus.BAD_REQUEST, "연수자 정보가 올바르지 않습니다.")
        if (response.statusCode() == 404) throw ExpectedException(HttpStatus.NOT_FOUND, "연수자를 찾지 못 했습니다.")
        if (response.statusCode() == 409) throw ExpectedException(HttpStatus.CONFLICT, "연수자 정보가 중복되어 신청할 수 없습니다. 관리자에게 문의해 주세요.")
        if (response.statusCode() != 200) badResponse("User")
        val traineeId = decode(response.body(), ResolveTraineeResponse::class.java).traineeId
        if (traineeId <= 0) badResponse("User")
        return traineeId
    }

    fun traineePreFormPeriod(expoId: String): FormPeriod {
        // 공개 폼 조회라 내부 토큰을 보내지 않는다(GetExpoValidationServiceImpl과 같은 경로)
        val response =
            send(formServiceUrl, "/forms/$expoId?type=TRAINEE&applicationType=PRE", "GET", HttpRequest.BodyPublishers.noBody(), false)
        if (response.statusCode() == 404) throw ExpectedException(HttpStatus.NOT_FOUND, "폼을 찾을 수 없습니다.")
        if (response.statusCode() != 200) badResponse("Form")
        return decode(response.body(), FormPeriod::class.java)
    }

    fun resolveOrCreateTrainee(
        expoId: String,
        request: ApplyTrainingProgramsWithTraineeRequest,
    ): Long {
        val body =
            ResolveOrCreateTraineeRequest(
                expoId = expoId,
                trainingId = request.trainingId,
                name = request.name,
                phoneNumber = request.phoneNumber,
                informationJson = request.informationJson,
                personalInformationStatus = request.personalInformationStatus,
            )
        val response = post(userServiceUrl, "/internal/trainees/resolve-or-create", body)
        if (response.statusCode() == 400) throw ExpectedException(HttpStatus.BAD_REQUEST, "연수자 정보가 올바르지 않습니다.")
        if (response.statusCode() == 409) throw ExpectedException(HttpStatus.CONFLICT, "연수자를 등록할 수 없는 박람회입니다.")
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

    fun executeOperation(operation: TrainingOperation) {
        val response =
            send(applicationServiceUrl, operation.path, operation.method, HttpRequest.BodyPublishers.ofString(operation.commandJson))
        when (response.statusCode()) {
            if (operation.type == TrainingOperationType.ADD) 201 else 204 -> {
                Unit
            }

            400 -> {
                throw ExpectedException(HttpStatus.BAD_REQUEST, "연수 신청 정보가 올바르지 않습니다.")
            }

            404 -> {
                throw ExpectedException(HttpStatus.NOT_FOUND, "연수 프로그램을 찾지 못했습니다.")
            }

            409 -> {
                throw ExpectedException(
                    HttpStatus.CONFLICT,
                    if (operation.type == TrainingOperationType.ADD) {
                        "이미 신청했거나 연수 프로그램의 정원이 찼습니다."
                    } else {
                        "삭제됐거나 정원이 찬 연수 프로그램이 있습니다."
                    },
                )
            }

            else -> {
                badResponse("Application")
            }
        }
    }

    fun applicationVersion(traineeId: Long): Long {
        val response = get(applicationServiceUrl, "/internal/training-program-applications/trainee/$traineeId/version")
        if (response.statusCode() != 200) badResponse("Application")
        return try {
            val version = mapper.readTree(response.body()).path("version")
            require(version.isIntegralNumber && version.canConvertToLong() && version.asLong() >= 0)
            version.asLong()
        } catch (exception: Exception) {
            badResponse("Application")
        }
    }

    fun operationReceipt(operation: TrainingOperation): TrainingOperationReceipt? {
        val response = get(applicationServiceUrl, "/internal/training-program-applications/operations/${operation.eventId}")
        if (response.statusCode() == 404) return null
        if (response.statusCode() != 200) badResponse("Application")
        return try {
            val json = mapper.readTree(response.body())
            val operationId = json.path("operationId")
            val type = json.path("operationType")
            val expoId = json.path("expoId")
            val traineeId = json.path("traineeId")
            val version = json.path("version")
            val changed = json.path("changed")
            val ids = json.path("programIds")
            val completedAt = json.path("completedAt")
            require(operationId.isString && UUID.fromString(operationId.asString()).toString() == operation.eventId)
            require(type.isString && type.asString() == operation.type.name)
            require(expoId.isString && expoId.asString() == operation.expoId)
            require(traineeId.isIntegralNumber && traineeId.canConvertToLong() && traineeId.asLong() == operation.traineeId)
            require(json.path("status").isString && json.path("status").asString() == "SUCCEEDED")
            require(version.isIntegralNumber && version.canConvertToLong() && version.asLong() >= 0 && changed.isBoolean)
            require(ids.isArray && completedAt.isString)
            val programIds =
                (0 until ids.size()).map { index ->
                    val it = ids[index]
                    require(it.isIntegralNumber && it.canConvertToLong() && it.asLong() > 0)
                    it.asLong()
                }
            require(programIds == programIds.distinct().sorted())
            val expectedResult = if (changed.asBoolean()) Math.addExact(operation.expectedVersion, 1) else operation.expectedVersion
            require(version.asLong() == expectedResult)
            val command = mapper.readTree(operation.commandJson)
            val programs = command.path("programs")
            val requested = (0 until programs.size()).map { programs[it].path("id").asLong() }.sorted()
            if (operation.type == TrainingOperationType.ADD) {
                require(changed.asBoolean() && programIds.containsAll(requested))
            } else {
                require(programIds == requested)
            }
            TrainingOperationReceipt(
                UUID.fromString(operation.eventId),
                operation.type,
                operation.expoId,
                operation.traineeId,
                version.asLong(),
                changed.asBoolean(),
                programIds,
                Instant.parse(completedAt.asString()),
            )
        } catch (exception: Exception) {
            badResponse("Application")
        }
    }

    fun applications(programId: Long): List<ProgramApplication> {
        val response = get(applicationServiceUrl, "/internal/training-program-applications/program/$programId")
        if (response.statusCode() != 200) badResponse("Application")
        return decode(response.body(), Array<ProgramApplication>::class.java).toList()
    }

    fun deleteApplications(programId: Long) {
        attendances.requireConfiguration()
        if (delete(applicationServiceUrl, "/internal/training-program-applications/program/$programId").statusCode() != 204) {
            badResponse("Application")
        }
        attendances.deleteTraining(programId)
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
        internal: Boolean = true,
    ): HttpResponse<String> {
        if (baseUrl.isBlank() || (internal && internalToken.isBlank())) {
            throw ExpectedException(HttpStatus.SERVICE_UNAVAILABLE, "연수 프로그램 내부 연동이 설정되지 않았습니다.")
        }
        try {
            val request =
                HttpRequest
                    .newBuilder(URI.create("${baseUrl.trimEnd('/')}$path"))
                    .timeout(Duration.ofSeconds(5))
                    .header("Content-Type", "application/json")
                    .method(method, body)
            if (internal) request.header("X-Internal-Token", internalToken)
            return http.send(request.build(), HttpResponse.BodyHandlers.ofString())
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
            requireNotNull(mapper.readValue(body, type))
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

    private data class ResolveOrCreateTraineeRequest(
        val expoId: String,
        val trainingId: String,
        val name: String,
        val phoneNumber: String,
        val informationJson: String,
        val personalInformationStatus: Boolean,
    )

    // Form 응답의 나머지 필드(dynamicForm 등)는 읽지 않는다
    data class FormPeriod(
        val startDate: Instant,
        val endDate: Instant,
    )

    private data class TraineeNamesRequest(
        val expoId: String,
        val traineeIds: List<Long>,
    )

    data class TraineeName(
        val traineeId: Long,
        val name: String,
    )

    data class ProgramApplication(
        val applicationId: Long,
        val traineeId: Long,
    )
}
