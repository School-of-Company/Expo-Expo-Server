package team.startup.expo.global.common.time

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

// 요청 DTO의 @JsonFormat(pattern = "yyyy-MM-dd HH:mm")과 같은 형식으로 저장한다
private val PROGRAM_DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

fun LocalDateTime.toProgramDateTime(): String = format(PROGRAM_DATE_TIME_FORMATTER)
