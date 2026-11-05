package team.startup.expo.global.attendance

import feign.Response
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Lazy
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import team.startup.expo.global.client.AttentionClient
import team.startup.expo.global.client.Downstream
import team.startup.expo.global.client.DownstreamCircuitBreakers
import team.startup.expo.global.client.bodyText
import team.startup.expo.global.exception.ExpectedException
import tools.jackson.databind.ObjectMapper
import java.io.IOException
import java.time.LocalTime
import java.time.format.DateTimeParseException

@Component
class ProgramAttendanceClient(
    @Value("\${expo.attention.service-url:}") private val serviceUrl: String,
    @Value("\${expo.attention.internal-token:}") private val internalToken: String,
    private val mapper: ObjectMapper,
    @Lazy private val attention: AttentionClient,
    private val breakers: DownstreamCircuitBreakers,
) {
    fun requireConfiguration() {
        if (serviceUrl.isBlank() || internalToken.isBlank()) {
            throw ExpectedException(HttpStatus.SERVICE_UNAVAILABLE, "출석 내부 연동이 설정되지 않았습니다.")
        }
    }

    fun deleteStandard(programId: Long) = delete { attention.deleteStandard(programId) }

    fun deleteTraining(programId: Long) = delete { attention.deleteTraining(programId) }

    fun deleteExpo(expoId: String) = delete { attention.deleteExpo(expoId) }

    private fun delete(block: () -> Response) {
        requireConfiguration()
        try {
            if (breakers.call(Downstream.ATTENTION, block = block).status() != 204) {
                throw ExpectedException(HttpStatus.BAD_GATEWAY, "출석 서비스 삭제 응답을 확인할 수 없습니다.")
            }
        } catch (exception: InterruptedException) {
            Thread.currentThread().interrupt()
            throw ExpectedException(HttpStatus.SERVICE_UNAVAILABLE, "출석 내부 연동이 중단됐습니다.")
        } catch (exception: IOException) {
            throw ExpectedException(HttpStatus.SERVICE_UNAVAILABLE, "출석 서비스에 연결할 수 없습니다.")
        }
    }

    fun standard(programId: Long): Map<Long, Attendance> =
        byPerson(
            fetch(Array<StandardAttendance>::class.java) { attention.standard(programId) }
                .map { Record(it.participantId, it.entryTime, it.leaveTime) },
        )

    fun training(programId: Long): Map<Long, Attendance> =
        byPerson(
            fetch(Array<TrainingAttendance>::class.java) { attention.training(programId) }
                .map { Record(it.traineeId, it.entryTime, it.leaveTime) },
        )

    // v1처럼 프로그램마다 한 사람에 출석 기록 하나다. 같은 사람이 두 번 오면 손상된 응답으로 본다.
    private fun byPerson(records: List<Record>): Map<Long, Attendance> {
        val attendances =
            records.associate { record ->
                val personId = record.personId?.takeIf { it > 0 } ?: invalid()
                val entryTime = record.entryTime ?: invalid()
                personId to Attendance(time(entryTime), record.leaveTime?.let(::time))
            }
        if (attendances.size != records.size) invalid()
        return attendances
    }

    private fun time(value: String): String {
        if (!TIME.matches(value)) invalid()
        try {
            LocalTime.parse(value)
        } catch (exception: DateTimeParseException) {
            invalid()
        }
        return value
    }

    private fun <T> fetch(
        type: Class<Array<T>>,
        block: () -> Response,
    ): List<T> {
        requireConfiguration()
        val response =
            try {
                breakers.call(Downstream.ATTENTION, idempotent = true, block)
            } catch (exception: InterruptedException) {
                Thread.currentThread().interrupt()
                throw ExpectedException(HttpStatus.SERVICE_UNAVAILABLE, "출석 내부 연동이 중단됐습니다.")
            } catch (exception: IOException) {
                throw ExpectedException(HttpStatus.SERVICE_UNAVAILABLE, "출석 서비스에 연결할 수 없습니다.")
            }
        if (response.status() != 200) throw ExpectedException(HttpStatus.BAD_GATEWAY, "Attention 서비스 응답을 확인할 수 없습니다.")
        return try {
            mapper.readValue(response.bodyText(), type)?.toList()?.takeIf { values -> values.none { (it as Any?) == null } } ?: invalid()
        } catch (exception: ExpectedException) {
            throw exception
        } catch (exception: Exception) {
            invalid()
        }
    }

    private fun invalid(): Nothing = throw ExpectedException(HttpStatus.BAD_GATEWAY, "출석 서비스 응답 형식이 올바르지 않습니다.")

    data class Attendance(
        val entryTime: String,
        val leaveTime: String?,
    )

    private data class StandardAttendance(
        val participantId: Long?,
        val entryTime: String?,
        val leaveTime: String?,
    )

    private data class TrainingAttendance(
        val traineeId: Long?,
        val entryTime: String?,
        val leaveTime: String?,
    )

    private data class Record(
        val personId: Long?,
        val entryTime: String?,
        val leaveTime: String?,
    )

    private companion object {
        val TIME = Regex("""\d{2}:\d{2}""")
    }
}
