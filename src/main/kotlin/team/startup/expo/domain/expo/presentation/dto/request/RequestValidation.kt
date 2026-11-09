package team.startup.expo.domain.expo.presentation.dto.request

import org.springframework.http.HttpStatus
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

// #258: 프로그램 일정은 박람회 기간(날짜 단위) 안이어야 한다. named면 기간 밖 프로그램 제목을 문구에 덧붙인다.
fun validateProgramPeriod(
    startedDay: LocalDate,
    finishedDay: LocalDate,
    programs: List<Triple<String, LocalDateTime, LocalDateTime>>,
    named: Boolean = false,
) {
    val outside = programs.filter { (_, start, end) -> start.toLocalDate() < startedDay || end.toLocalDate() > finishedDay }
    if (outside.isEmpty()) return
    val message = "프로그램 일정은 박람회 기간($startedDay ~ $finishedDay) 안이어야 합니다."
    throw ExpectedException(
        HttpStatus.BAD_REQUEST,
        if (named) "$message (${outside.joinToString { it.first }})" else message,
    )
}

fun Iterable<Instant>.hasMicrosecondPrecision(): Boolean = all { it.nano % 1000 == 0 }
