package team.startup.expo.global.client

import io.github.resilience4j.circuitbreaker.CircuitBreaker
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry
import org.springframework.stereotype.Component
import java.io.IOException
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.time.Duration
import java.util.concurrent.TimeUnit

/** 서킷브레이커를 나누는 단위다. 한 서비스가 죽어도 다른 서비스 호출은 막지 않도록 서비스마다 회로를 따로 둔다. */
enum class Downstream {
    USER,
    APPLICATION,
    FORM,
    ATTENTION,
}

/** 회로가 열려 있어 호출하지 않았다. 기존 호출부가 연결 실패(`IOException`)를 503으로 바꾸므로 같은 503으로 빠르게 실패한다. */
class DownstreamUnavailableException(
    downstream: Downstream,
) : IOException("$downstream circuit is open")

/** 서비스가 5xx로 응답한 것을 회로에 실패로 알리기 위한 표지다. 호출부에는 던지지 않고 응답을 그대로 돌려준다. */
private class DownstreamServerError(
    val status: Int,
) : RuntimeException("downstream responded $status", null, false, false)

/**
 * 서비스 간 HTTP 호출에 서킷브레이커와 제한적 재시도를 입힌다.
 *
 * - 실패로 세는 것은 타임아웃·연결 실패(`IOException`)와 5xx뿐이다. 400/401/403/404/409 같은 4xx는 서비스가 정상적으로
 *   거절한 것이므로 성공으로 센다. 응답 본문 파싱·형식 오류는 이 클래스를 지난 뒤 호출부에서 일어나 세지 않는다.
 * - 재시도는 `idempotent = true`인 호출의 즉시 실패(연결 거부 등)에만 100ms 쉰 뒤 1회 한다. 재시도가 뒤따르는 첫 시도의 실패는
 *   회로에 세지 않고 마지막 시도만 세므로, 사용자 요청 1건은 회로에 1건으로 집계된다. 타임아웃은 이미 5초를 썼으므로 다시
 *   시도하지 않고, 쓰기(PUT/POST/DELETE)는 중복 실행될 수 있어 `idempotent = false`로 호출해 재시도하지 않는다.
 * - 응답은 상태 코드와 상관없이 호출부에 그대로 돌려줘서 기존 4xx/5xx 처리와 메시지를 바꾸지 않는다.
 *
 * 최근 20번 중 10번 이상 호출되고 절반 이상 실패하면 10초 동안 열려 호출하지 않고 바로 실패한다(User 서비스와 같은 값).
 */
@Component
class DownstreamCircuitBreakers {
    private val registry =
        CircuitBreakerRegistry.of(
            CircuitBreakerConfig
                .custom()
                .slidingWindowSize(WINDOW_SIZE)
                .minimumNumberOfCalls(MIN_CALLS)
                .failureRateThreshold(FAILURE_RATE_THRESHOLD)
                .waitDurationInOpenState(Duration.ofSeconds(OPEN_SECONDS))
                .build(),
        )

    fun of(downstream: Downstream): CircuitBreaker = registry.circuitBreaker(downstream.name.lowercase())

    /** 모든 회로를 닫힘으로 되돌리고 집계를 비운다. 같은 컨텍스트를 공유하는 테스트가 앞선 장애 시나리오를 물려받지 않게 한다. */
    fun resetAll() = registry.allCircuitBreakers.forEach { it.reset() }

    fun <T> send(
        downstream: Downstream,
        http: HttpClient,
        request: HttpRequest,
        handler: HttpResponse.BodyHandler<T>,
        idempotent: Boolean,
    ): HttpResponse<T> {
        try {
            return attempt(downstream, http, request, handler, retryIfImmediateFailure = idempotent)
        } catch (exception: RetryableFailure) {
            // 첫 시도의 실패는 회로에 세지 않았다. 잠깐 쉬고 한 번 더 시도하며 그 결과만 센다.
            Thread.sleep(RETRY_BACKOFF_MILLIS)
        }
        return attempt(downstream, http, request, handler, retryIfImmediateFailure = false)
    }

    /** 첫 시도가 즉시 실패해 재시도로 넘어가는 신호다. 호출부에는 전달되지 않는다. */
    private class RetryableFailure : RuntimeException(null, null, false, false)

    private fun retryable(exception: IOException): Boolean =
        exception !is HttpTimeoutException && exception !is DownstreamUnavailableException

    private fun <T> attempt(
        downstream: Downstream,
        http: HttpClient,
        request: HttpRequest,
        handler: HttpResponse.BodyHandler<T>,
        retryIfImmediateFailure: Boolean,
    ): HttpResponse<T> {
        val breaker = of(downstream)
        if (!breaker.tryAcquirePermission()) throw DownstreamUnavailableException(downstream)
        val start = System.nanoTime()
        val response =
            try {
                http.send(request, handler)
            } catch (exception: IOException) {
                if (retryIfImmediateFailure && retryable(exception)) {
                    // 재시도가 뒤따르므로 이 실패는 세지 않는다. 사용자 요청 1건이 회로에 2건으로 잡히는 것을 막는다.
                    breaker.releasePermission()
                    throw RetryableFailure()
                }
                breaker.onError(System.nanoTime() - start, TimeUnit.NANOSECONDS, exception)
                throw exception
            } catch (exception: Throwable) {
                // 중단이나 요청 구성 오류는 서비스 상태와 무관하므로 허용량만 돌려준다
                breaker.releasePermission()
                throw exception
            }
        val elapsed = System.nanoTime() - start
        if (response.statusCode() >= SERVER_ERROR) {
            breaker.onError(elapsed, TimeUnit.NANOSECONDS, DownstreamServerError(response.statusCode()))
        } else {
            breaker.onSuccess(elapsed, TimeUnit.NANOSECONDS)
        }
        return response
    }

    private companion object {
        const val WINDOW_SIZE = 20
        const val MIN_CALLS = 10
        const val FAILURE_RATE_THRESHOLD = 50f
        const val OPEN_SECONDS = 10L
        const val SERVER_ERROR = 500
        const val RETRY_BACKOFF_MILLIS = 100L
    }
}
