package team.startup.expo.global.client

import feign.RequestInterceptor
import feign.Retryer
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean

// @Configuration을 붙이지 않는다. 각 Feign 자식 컨텍스트에만 토큰을 등록한다.
class UserClientConfiguration {
    @Bean
    fun internalToken(
        @Value("\${USER_INTERNAL_TOKEN:}") token: String,
    ): RequestInterceptor = InternalTokenInterceptor(token)
}

class ApplicationClientConfiguration {
    @Bean
    fun internalToken(
        @Value("\${APPLICATION_INTERNAL_TOKEN:}") token: String,
    ): RequestInterceptor = InternalTokenInterceptor(token)
}

class FormClientConfiguration {
    @Bean
    fun internalToken(
        @Value("\${FORM_INTERNAL_TOKEN:}") token: String,
    ): RequestInterceptor = InternalTokenInterceptor(token)
}

class AttentionClientConfiguration {
    @Bean
    fun internalToken(
        @Value("\${expo.attention.internal-token:}") token: String,
    ): RequestInterceptor = InternalTokenInterceptor(token)
}

class PublicClientConfiguration {
    @Bean
    fun retryer(): Retryer = Retryer.NEVER_RETRY
}
