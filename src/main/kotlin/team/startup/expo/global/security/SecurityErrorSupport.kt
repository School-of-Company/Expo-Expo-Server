package team.startup.expo.global.security

import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.MediaType
import team.startup.expo.global.exception.ErrorResponse
import tools.jackson.databind.ObjectMapper

object SecurityErrorSupport {
    fun write(
        mapper: ObjectMapper,
        response: HttpServletResponse,
        status: Int,
        message: String,
    ) {
        response.status = status
        response.characterEncoding = Charsets.UTF_8.name()
        response.contentType = MediaType.APPLICATION_JSON_VALUE
        mapper.writeValue(response.writer, ErrorResponse(status = status, message = message))
    }
}
