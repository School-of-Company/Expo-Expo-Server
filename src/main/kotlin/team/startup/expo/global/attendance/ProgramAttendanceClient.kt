package team.startup.expo.global.attendance

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
import java.time.LocalTime
import java.time.format.DateTimeParseException

// Attention 출석 조회 클라이언트. 경로와 DTO는 Attention #5와 합의 전 초안이라 공급자 구현에 맞춰 바뀔 수 있다.
@Component
class ProgramAttendanceClient(
    @Value("\${expo.attention.service-url:}") private val serviceUrl: String,
    @Value("\${expo.attention.internal-token:}") private val internalToken: String,
    private val mapper: ObjectMapper,
) {
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build()

    fun standard(programId: Long): Map<Long, Attendance> =
        byPerson(
            fetch("/internal/program-attendances/standard/$programId", Array<StandardAttendance>::class.java)
                .map { Record(it.participantId, it.entryTime, it.leaveTime) },
        )

    fun training(programId: Long): Map<Long, Attendance> =
        byPerson(
            fetch("/internal/program-attendances/training/$programId", Array<TrainingAttendance>::class.java)
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
        path: String,
        type: Class<Array<T>>,
    ): List<T> {
        if (serviceUrl.isBlank() || internalToken.isBlank()) {
            throw ExpectedException(HttpStatus.SERVICE_UNAVAILABLE, "출석 내부 연동이 설정되지 않았습니다.")
        }
        val response =
            try {
                val request =
                    HttpRequest
                        .newBuilder(URI.create("${serviceUrl.trimEnd('/')}$path"))
                        .timeout(Duration.ofSeconds(5))
                        .header("X-Internal-Token", internalToken)
                        .GET()
                        .build()
                http.send(request, HttpResponse.BodyHandlers.ofString())
            } catch (exception: InterruptedException) {
                Thread.currentThread().interrupt()
                throw ExpectedException(HttpStatus.SERVICE_UNAVAILABLE, "출석 내부 연동이 중단됐습니다.")
            } catch (exception: IOException) {
                throw ExpectedException(HttpStatus.SERVICE_UNAVAILABLE, "출석 서비스에 연결할 수 없습니다.")
            }
        if (response.statusCode() != 200) throw ExpectedException(HttpStatus.BAD_GATEWAY, "Attention 서비스 응답을 확인할 수 없습니다.")
        return try {
            mapper.readValue(response.body(), type)?.toList()?.takeIf { values -> values.none { (it as Any?) == null } } ?: invalid()
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
