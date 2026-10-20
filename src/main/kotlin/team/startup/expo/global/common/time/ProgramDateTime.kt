package team.startup.expo.global.common.time

import java.time.LocalDateTime

// 프로그램 일시 저장값은 원본 계약의 "yyyy-MM-ddTHH:mm"이고, 예전 일부 행은 "yyyy-MM-dd HH:mm"이다
fun String.toProgramLocalDateTime(): LocalDateTime = LocalDateTime.parse(replace(' ', 'T'))
