package team.startup.expo.domain.expo.service

import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Lazy
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import team.startup.expo.global.client.AdminUserClient
import team.startup.expo.global.client.Downstream
import team.startup.expo.global.client.DownstreamCircuitBreakers
import team.startup.expo.global.exception.ExpectedException
import tools.jackson.core.JacksonException
import tools.jackson.databind.ObjectMapper
import java.io.IOException

@Component
class AdminAuthorityClient(
    @Value("\${expo.standard.user-service-url:}") private val userServiceUrl: String,
    @Lazy private val users: AdminUserClient,
    private val breakers: DownstreamCircuitBreakers,
    private val mapper: ObjectMapper = ObjectMapper(),
) {
    fun requireCurrentAdmin(authorization: String) {
        isSuperAdmin(authorization)
    }

    fun isSuperAdmin(authorization: String): Boolean {
        if (userServiceUrl.isBlank()) unavailable()
        try {
            breakers.call(Downstream.USER, idempotent = true) { users.current(authorization) }.use { response ->
                return when (response.status()) {
                    200 -> {
                        response.body()?.asInputStream()?.use { input ->
                            val body = mapper.readTree(input)
                            body?.get("superAdmin")?.takeIf { it.isBoolean }?.asBoolean() ?: false
                        } ?: false
                    }

                    401 -> {
                        throw ExpectedException(HttpStatus.UNAUTHORIZED, "관리자 인증이 유효하지 않습니다.")
                    }

                    403 -> {
                        throw ExpectedException(HttpStatus.FORBIDDEN, "접근 권한이 없습니다.")
                    }

                    else -> {
                        unavailable()
                    }
                }
            }
        } catch (exception: IOException) {
            unavailable()
        } catch (exception: JacksonException) {
            unavailable()
        } catch (exception: InterruptedException) {
            Thread.currentThread().interrupt()
            unavailable()
        }
    }

    private fun unavailable(): Nothing = throw ExpectedException(HttpStatus.SERVICE_UNAVAILABLE, "현재 관리자 권한을 확인할 수 없습니다.")
}
