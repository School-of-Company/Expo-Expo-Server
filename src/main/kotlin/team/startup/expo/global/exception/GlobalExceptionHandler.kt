package team.startup.expo.global.exception

import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

@RestControllerAdvice
class GlobalExceptionHandler {
    @ExceptionHandler(MethodArgumentNotValidException::class, HttpMessageNotReadableException::class)
    fun handleBadRequest(): ResponseEntity<ErrorResponse> = errorResponse(HttpStatus.BAD_REQUEST, "잘못된 요청입니다.")

    @ExceptionHandler(ExpectedException::class)
    fun handleExpectedException(exception: ExpectedException): ResponseEntity<ErrorResponse> =
        errorResponse(exception.status, exception.message)

    @ExceptionHandler(Exception::class)
    fun handleUnexpected(exception: Exception): ResponseEntity<ErrorResponse> {
        logger.error("Unhandled API exception type: {}", exception.javaClass.name)
        return errorResponse(HttpStatus.INTERNAL_SERVER_ERROR, "서버 오류가 발생했습니다.")
    }

    private fun errorResponse(
        status: HttpStatus,
        message: String,
    ): ResponseEntity<ErrorResponse> =
        ResponseEntity
            .status(status)
            .body(ErrorResponse(status = status.value(), message = message))

    private companion object {
        val logger = LoggerFactory.getLogger(GlobalExceptionHandler::class.java)
    }
}
