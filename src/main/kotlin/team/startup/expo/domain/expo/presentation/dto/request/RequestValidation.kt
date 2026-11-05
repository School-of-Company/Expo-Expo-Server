package team.startup.expo.domain.expo.presentation.dto.request

import org.springframework.http.HttpStatus
import team.startup.expo.global.exception.ExpectedException
import java.time.Instant
import java.time.LocalDate

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

fun Iterable<Instant>.hasMicrosecondPrecision(): Boolean = all { it.nano % 1000 == 0 }
