package team.startup.expo.domain.expo.service

import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import team.startup.expo.global.exception.ExpectedException
import tools.jackson.databind.ObjectMapper
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

@Component
class ExpoDeletionClient(
    @Value("\${expo.standard.application-service-url:}") private val applicationUrl: String,
    @Value("\${expo.form-service-url:}") private val formUrl: String,
    @Value("\${expo.standard.user-service-url:}") private val userUrl: String,
    @Value("\${expo.delete-internal-token:}") private val internalToken: String,
    private val mapper: ObjectMapper,
) {
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build()

    fun requireConfiguration() {
        if (applicationUrl.isBlank() || formUrl.isBlank() || userUrl.isBlank() || internalToken.isBlank()) {
            throw ExpectedException(HttpStatus.SERVICE_UNAVAILABLE, "박람회 삭제 내부 연동이 설정되지 않았습니다.")
        }
    }

    fun deleteApplications(
        expoId: String,
        standardIds: List<Long>,
        trainingIds: List<Long>,
    ) {
        send(
            applicationUrl,
            "/internal/expos/$expoId/purge",
            "POST",
            HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(ProgramIds(standardIds, trainingIds))),
            "Application",
        )
    }

    fun deleteForms(expoId: String) = delete(formUrl, expoId, "Form")

    fun deleteUsers(expoId: String) = delete(userUrl, expoId, "User")

    private fun delete(
        baseUrl: String,
        expoId: String,
        service: String,
    ) {
        send(baseUrl, "/internal/expos/$expoId", "DELETE", HttpRequest.BodyPublishers.noBody(), service)
    }

    private fun send(
        baseUrl: String,
        path: String,
        method: String,
        body: HttpRequest.BodyPublisher,
        service: String,
    ) {
        try {
            val request =
                HttpRequest
                    .newBuilder(URI.create("${baseUrl.trimEnd('/')}$path"))
                    .timeout(Duration.ofSeconds(5))
                    .header("Content-Type", "application/json")
                    .header("X-Internal-Token", internalToken)
                    .method(method, body)
                    .build()
            if (http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode() != 204) {
                throw ExpectedException(HttpStatus.BAD_GATEWAY, "$service 서비스 삭제 응답을 확인할 수 없습니다.")
            }
        } catch (exception: ExpectedException) {
            throw exception
        } catch (exception: InterruptedException) {
            Thread.currentThread().interrupt()
            throw ExpectedException(HttpStatus.SERVICE_UNAVAILABLE, "$service 서비스 삭제 요청이 중단됐습니다.")
        } catch (exception: IOException) {
            throw ExpectedException(HttpStatus.SERVICE_UNAVAILABLE, "$service 서비스에 연결할 수 없습니다.")
        }
    }

    private data class ProgramIds(
        val standardProgramIds: List<Long>,
        val trainingProgramIds: List<Long>,
    )
}
