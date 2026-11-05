package team.startup.expo.domain.training.service

import org.springframework.http.HttpStatus
import team.startup.expo.global.exception.ExpectedException

object TrainingOperationSupport {
    fun isDefiniteRejection(exception: ExpectedException): Boolean = exception.status in DEFINITE_REJECTIONS

    private val DEFINITE_REJECTIONS = setOf(HttpStatus.BAD_REQUEST, HttpStatus.NOT_FOUND, HttpStatus.CONFLICT)
}
