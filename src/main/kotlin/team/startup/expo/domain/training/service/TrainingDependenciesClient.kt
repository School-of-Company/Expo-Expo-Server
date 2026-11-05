package team.startup.expo.domain.training.service

import feign.Response
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Lazy
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import team.startup.expo.global.attendance.ProgramAttendanceClient
import team.startup.expo.global.client.Downstream
import team.startup.expo.global.client.DownstreamCircuitBreakers
import team.startup.expo.global.client.PublicFormClient
import team.startup.expo.global.client.TrainingApplicationClient
import team.startup.expo.global.client.TrainingUserClient
import team.startup.expo.global.client.bodyText
import team.startup.expo.global.exception.ExpectedException
import tools.jackson.databind.ObjectMapper
import java.io.IOException
import java.time.Instant
import java.util.UUID

@Component
class TrainingDependenciesClient(
    @Value("\${expo.training.user-service-url:}") private val userServiceUrl: String,
    @Value("\${expo.training.application-service-url:}") private val applicationServiceUrl: String,
    @Value("\${USER_INTERNAL_TOKEN:}") private val userToken: String,
    @Value("\${APPLICATION_INTERNAL_TOKEN:}") private val applicationToken: String,
    @Value("\${expo.form-service-url:}") private val formServiceUrl: String,
    private val mapper: ObjectMapper,
    private val attendances: ProgramAttendanceClient,
    @Lazy private val users: TrainingUserClient,
    @Lazy private val applications: TrainingApplicationClient,
    @Lazy private val forms: PublicFormClient,
    private val breakers: DownstreamCircuitBreakers,
) {
    fun requireApplicationConfiguration() {
        if (applicationServiceUrl.isBlank() || applicationToken.isBlank()) {
            throw ExpectedException(HttpStatus.SERVICE_UNAVAILABLE, "연수 프로그램 내부 연동이 설정되지 않았습니다.")
        }
    }

    fun traineePhoneNumber(
        expoId: String,
        traineeId: Long,
    ): String {
        val response =
            call(Downstream.USER, userServiceUrl, userToken, idempotent = true) { users.details(expoId, traineeId - 1, 1) }
        if (response.status() != 200) badResponse("User")
        return try {
            val items = mapper.readTree(response.bodyText()).path("items")
            require(items.isArray && items.size() == 1)
            val id = items[0].path("traineeId")
            val notificationPhone = items[0].path("notificationPhoneNumber")
            val phone = if (notificationPhone.isMissingNode || notificationPhone.isNull) items[0].path("phoneNumber") else notificationPhone
            require(id.isIntegralNumber && id.canConvertToLong() && id.asLong() == traineeId && phone.isString)
            val stored = phone.asString()
            require(stored.matches(STORED_PHONE))
            val digits = stored.replace("-", "").replace(" ", "")
            require(digits.matches(PHONE))
            digits
        } catch (exception: Exception) {
            throw ExpectedException(HttpStatus.BAD_GATEWAY, "연수 문자 수신자를 확인할 수 없습니다.")
        }
    }

    fun resolveTrainee(
        expoId: String,
        trainingId: String,
    ): Long {
        val response =
            call(Downstream.USER, userServiceUrl, userToken) {
                users.resolve(mapper.writeValueAsString(ResolveTraineeRequest(expoId, trainingId)))
            }
        if (response.status() == 400) throw ExpectedException(HttpStatus.BAD_REQUEST, "연수자 정보가 올바르지 않습니다.")
        if (response.status() == 404) throw ExpectedException(HttpStatus.NOT_FOUND, "연수자를 찾지 못 했습니다.")
        if (response.status() == 409) throw ExpectedException(HttpStatus.CONFLICT, "연수자 정보가 중복되어 신청할 수 없습니다. 관리자에게 문의해 주세요.")
        if (response.status() != 200) badResponse("User")
        val traineeId = decode(response.bodyText(), ResolveTraineeResponse::class.java).traineeId
        if (traineeId <= 0) badResponse("User")
        return traineeId
    }

    fun traineePreFormPeriod(expoId: String): FormPeriod {
        // 공개 폼 조회라 내부 토큰을 보내지 않는다(GetExpoValidationServiceImpl과 같은 경로)
        val response =
            call(Downstream.FORM, formServiceUrl, "", idempotent = true, internal = false) {
                forms.lookup("forms", expoId, "TRAINEE", "PRE", MediaType.APPLICATION_JSON_VALUE)
            }
        if (response.status() == 404) throw ExpectedException(HttpStatus.NOT_FOUND, "폼을 찾을 수 없습니다.")
        if (response.status() != 200) badResponse("Form")
        return decode(response.bodyText(), FormPeriod::class.java)
    }

    fun resolveOrCreateTrainee(request: ResolveOrCreateTraineeRequest): Long {
        val response =
            call(Downstream.USER, userServiceUrl, userToken) {
                users.resolveOrCreate(mapper.writeValueAsString(request))
            }
        if (response.status() == 400) throw ExpectedException(HttpStatus.BAD_REQUEST, "연수자 정보가 올바르지 않습니다.")
        if (response.status() == 409) throw ExpectedException(HttpStatus.CONFLICT, "연수자를 등록할 수 없는 박람회입니다.")
        if (response.status() != 200) badResponse("User")
        val traineeId = decode(response.bodyText(), ResolveTraineeResponse::class.java).traineeId
        if (traineeId <= 0) badResponse("User")
        return traineeId
    }

    fun traineeNames(
        expoId: String,
        traineeIds: List<Long>,
    ): List<TraineeName> {
        val response =
            call(Downstream.USER, userServiceUrl, userToken) {
                users.names(mapper.writeValueAsString(TraineeNamesRequest(expoId, traineeIds)))
            }
        if (response.status() != 200) badResponse("User")
        return decode(response.bodyText(), Array<TraineeName>::class.java).toList()
    }

    fun executeOperation(operation: TrainingOperation) {
        val response =
            call(Downstream.APPLICATION, applicationServiceUrl, applicationToken) {
                if (operation.type ==
                    TrainingOperationType.ADD
                ) {
                    applications.add(operation.commandJson)
                } else {
                    applications.replace(operation.traineeId, operation.commandJson)
                }
            }
        when (response.status()) {
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
        val response =
            call(Downstream.APPLICATION, applicationServiceUrl, applicationToken, idempotent = true) {
                applications.version(traineeId)
            }
        if (response.status() != 200) badResponse("Application")
        return try {
            val version = mapper.readTree(response.bodyText()).path("version")
            require(version.isIntegralNumber && version.canConvertToLong() && version.asLong() >= 0)
            version.asLong()
        } catch (exception: Exception) {
            badResponse("Application")
        }
    }

    fun operationReceipt(operation: TrainingOperation): TrainingOperationReceipt? {
        val response =
            call(Downstream.APPLICATION, applicationServiceUrl, applicationToken, idempotent = true) {
                applications.receipt(operation.eventId)
            }
        if (response.status() == 404) return null
        if (response.status() != 200) badResponse("Application")
        return try {
            val json = mapper.readTree(response.bodyText())
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
        val response =
            call(Downstream.APPLICATION, applicationServiceUrl, applicationToken, idempotent = true) {
                applications.applications(programId)
            }
        if (response.status() != 200) badResponse("Application")
        return decode(response.bodyText(), Array<ProgramApplication>::class.java).toList()
    }

    fun deleteApplications(programId: Long) {
        attendances.requireConfiguration()
        if (call(Downstream.APPLICATION, applicationServiceUrl, applicationToken) {
                applications.deleteApplications(programId)
            }.status() !=
            204
        ) {
            badResponse("Application")
        }
        attendances.deleteTraining(programId)
    }

    private fun call(
        downstream: Downstream,
        baseUrl: String,
        token: String,
        idempotent: Boolean = false,
        internal: Boolean = true,
        block: () -> Response,
    ): Response {
        if (baseUrl.isBlank() || (internal && token.isBlank())) {
            throw ExpectedException(HttpStatus.SERVICE_UNAVAILABLE, "연수 프로그램 내부 연동이 설정되지 않았습니다.")
        }
        try {
            return breakers.call(downstream, idempotent, block)
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

    data class ResolveOrCreateTraineeRequest(
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

    private companion object {
        val STORED_PHONE = Regex("[0-9 -]+")
        val PHONE = Regex("01[0-9]{8,9}")
    }
}
