package team.startup.expo.support

import feign.Feign
import feign.Request
import feign.Retryer
import feign.http2client.Http2Client
import org.springframework.cloud.openfeign.support.SpringMvcContract
import team.startup.expo.global.client.InternalTokenInterceptor
import java.net.http.HttpClient
import java.time.Duration
import java.util.concurrent.TimeUnit

inline fun <reified T> feignClient(
    url: String,
    token: String = "",
    readTimeoutMillis: Long = 5_000,
): T =
    Feign
        .builder()
        .client(Http2Client(HttpClient.newBuilder().connectTimeout(Duration.ofMillis(3_000)).build()))
        .contract(SpringMvcContract())
        .retryer(Retryer.NEVER_RETRY)
        .options(Request.Options(3_000, TimeUnit.MILLISECONDS, readTimeoutMillis, TimeUnit.MILLISECONDS, false))
        .requestInterceptor(InternalTokenInterceptor(token))
        .target(T::class.java, url.ifBlank { "http://unconfigured.invalid" })
