package team.startup.expo.domain.expo.service

import jakarta.annotation.PreDestroy
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Lazy
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import team.startup.expo.global.client.Downstream
import team.startup.expo.global.client.DownstreamCircuitBreakers
import team.startup.expo.global.client.PublicFormClient
import team.startup.expo.global.exception.ExpectedException
import java.io.IOException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors

@Component
class FormLookupClient(
    @Value("\${expo.form-service-url:}") private val formServiceUrl: String,
    @Lazy private val forms: PublicFormClient,
    private val breakers: DownstreamCircuitBreakers,
) {
    private val lookupExecutor = Executors.newFixedThreadPool(8)

    fun requireConfiguration() {
        if (formServiceUrl.isBlank()) {
            throw ExpectedException(HttpStatus.SERVICE_UNAVAILABLE, "Form 서비스 연결이 설정되지 않았습니다.")
        }
    }

    @PreDestroy
    fun shutdown() {
        lookupExecutor.shutdown()
    }

    fun lookup(
        resource: String,
        expoId: String,
        type: String,
        applicationType: String? = null,
    ): CompletableFuture<Boolean> = CompletableFuture.supplyAsync({ exists(resource, expoId, type, applicationType) }, lookupExecutor)

    private fun exists(
        resource: String,
        expoId: String,
        type: String,
        applicationType: String?,
    ): Boolean {
        try {
            return when (
                breakers
                    .call(Downstream.FORM, idempotent = true) {
                        forms.lookup(resource, expoId, type, applicationType)
                    }.status()
            ) {
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
