package team.startup.expo.global.logging

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.Ordered

/** `logging.http-access.enabled`(env `LOGGING_HTTP_ACCESS_ENABLED`, 기본 true)가 false면 접근 로그를 끈다. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "logging.http-access", name = ["enabled"], havingValue = "true", matchIfMissing = true)
class HttpAccessLogConfig {
    @Bean
    fun httpAccessLogFilter(): FilterRegistrationBean<HttpAccessLogFilter> =
        FilterRegistrationBean(HttpAccessLogFilter()).apply {
            // Spring Security 필터 체인(-100)보다 먼저 돌아야 401·403 거절도 남는다
            order = Ordered.HIGHEST_PRECEDENCE + 10
        }
}
