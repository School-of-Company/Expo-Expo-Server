package team.startup.expo.domain.expo.presentation.dto.request

import org.springframework.http.HttpStatus
import team.startup.expo.domain.expo.entity.Expo
import team.startup.expo.global.exception.ExpectedException
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime

const val PROGRAM_TITLE_MAX_LENGTH = 50
const val MAX_PAGE_SIZE = 100

fun validateExpoPeriod(
    startedDay: LocalDate,
    finishedDay: LocalDate,
) {
    if (finishedDay.isBefore(startedDay)) {
        throw ExpectedException(HttpStatus.BAD_REQUEST, "박람회 종료일은 시작일보다 빠를 수 없습니다.")
    }
}

data class ProgramSchedule(
    val title: String,
    val startedAt: LocalDateTime,
    val endedAt: LocalDateTime,
)

// 프로그램 일정은 날짜 단위로 박람회 기간 안이어야 하고, 오류 문구에는 기간 밖 프로그램 제목을 덧붙인다.
fun validateProgramPeriod(
    startedDay: LocalDate,
    finishedDay: LocalDate,
    programs: List<ProgramSchedule>,
) {
    val outside = programs.filter { it.startedAt.toLocalDate() < startedDay || it.endedAt.toLocalDate() > finishedDay }
    if (outside.isEmpty()) return
    throw ExpectedException(
        HttpStatus.BAD_REQUEST,
        "프로그램 일정은 박람회 기간($startedDay ~ $finishedDay) 안이어야 합니다. (${outside.joinToString { it.title }})",
    )
}

// 저장된 기간은 박람회 생성·수정이 LocalDate.toString()으로만 쓴다.
fun Expo.validateProgramPeriod(programs: List<ProgramSchedule>) =
    validateProgramPeriod(LocalDate.parse(startedDay), LocalDate.parse(finishedDay), programs)

fun Iterable<Instant>.hasMicrosecondPrecision(): Boolean = all { it.nano % 1000 == 0 }
