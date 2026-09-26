package team.startup.expo.global.exception

import org.springframework.http.HttpStatus

class ExpectedException(
    val status: HttpStatus,
    override val message: String,
) : RuntimeException(message)
