package team.startup.expo.domain.expo.service.impl

import jakarta.annotation.PreDestroy
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import team.startup.expo.domain.expo.presentation.dto.response.ExpoValidResponse
import team.startup.expo.domain.expo.presentation.dto.response.ExpoValidationResponse
import team.startup.expo.domain.expo.repository.ExpoRepository
import team.startup.expo.domain.expo.service.GetExpoValidationService
import team.startup.expo.global.exception.ExpectedException
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.Executors

@Service
class GetExpoValidationServiceImpl(
    private val expoRepository: ExpoRepository,
    @Value("\${expo.form-service-url:}") private val formServiceUrl: String,
) : GetExpoValidationService {
    private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build()
    private val lookupExecutor = Executors.newFixedThreadPool(8)

    override fun execute(): ExpoValidationResponse {
        val expoIds = expoRepository.findAll().map { it.id }
        if (expoIds.isNotEmpty() && formServiceUrl.isBlank()) {
            throw ExpectedException(HttpStatus.SERVICE_UNAVAILABLE, "Form 서비스 연결이 설정되지 않았습니다.")
        }

        val results =
            expoIds.map { expoId ->
                val preStandard = lookup("forms", expoId, "type=STANDARD&applicationType=PRE")
                val siteStandard = lookup("forms", expoId, "type=STANDARD&applicationType=FIELD")
                val preTrainee = lookup("forms", expoId, "type=TRAINEE&applicationType=PRE")
                val fieldTrainee = lookup("forms", expoId, "type=TRAINEE&applicationType=FIELD")
                val standardSurvey = lookup("surveys", expoId, "type=STANDARD")
                val traineeSurvey = lookup("surveys", expoId, "type=TRAINEE")
                CompletableFuture
                    .allOf(preStandard, siteStandard, preTrainee, fieldTrainee, standardSurvey, traineeSurvey)
                    .thenApply {
                        ExpoValidResponse(
                            expoId = expoId,
                            preStandardFormCreatedStatus = preStandard.join(),
                            siteStandardFormCreatedStatus = siteStandard.join(),
                            traineeFormCreatedStatus = preTrainee.join() || fieldTrainee.join(),
                            standardSurveyCreatedStatus = standardSurvey.join(),
                            traineeSurveyCreatedStatus = traineeSurvey.join(),
                        )
                    }
            }
        try {
            return ExpoValidationResponse(results.map { it.join() })
        } catch (exception: CompletionException) {
            throw exception.cause as? ExpectedException
                ?: ExpectedException(HttpStatus.BAD_GATEWAY, "Form 서비스에 연결할 수 없습니다.")
        }
    }

    @PreDestroy
    fun shutdown() {
        lookupExecutor.shutdown()
    }

    private fun lookup(
        resource: String,
        expoId: String,
        query: String,
    ): CompletableFuture<Boolean> = CompletableFuture.supplyAsync({ exists(resource, expoId, query) }, lookupExecutor)

    private fun exists(
        resource: String,
        expoId: String,
        query: String,
    ): Boolean {
        try {
            val request =
                HttpRequest
                    .newBuilder(URI.create("${formServiceUrl.trimEnd('/')}/$resource/$expoId?$query"))
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build()
            return when (client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode()) {
                200 -> true
                404 -> false
                else -> throw ExpectedException(HttpStatus.BAD_GATEWAY, "Form 서비스 응답을 확인할 수 없습니다.")
            }
        } catch (exception: ExpectedException) {
            throw exception
        } catch (exception: InterruptedException) {
            Thread.currentThread().interrupt()
            throw ExpectedException(HttpStatus.SERVICE_UNAVAILABLE, "Form 서비스 연결이 중단됐습니다.")
        } catch (exception: IOException) {
            throw ExpectedException(HttpStatus.SERVICE_UNAVAILABLE, "Form 서비스에 연결할 수 없습니다.")
        }
    }
}
