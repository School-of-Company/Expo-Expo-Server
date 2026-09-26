package team.startup.expo.global.security

import jakarta.servlet.http.HttpServletResponse
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.web.SecurityFilterChain

@Configuration
@EnableWebSecurity
class SecurityConfig {
    @Bean
    fun securityFilterChain(http: HttpSecurity): SecurityFilterChain {
        http
            .csrf { it.disable() }
            .cors { it.disable() }
            .formLogin { it.disable() }
            .httpBasic { it.disable() }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .exceptionHandling { exceptions ->
                exceptions
                    .authenticationEntryPoint { _, response, _ ->
                        writeError(response, HttpServletResponse.SC_UNAUTHORIZED, "인증이 필요합니다.")
                    }.accessDeniedHandler { _, response, _ ->
                        writeError(response, HttpServletResponse.SC_FORBIDDEN, "접근 권한이 없습니다.")
                    }
            }.authorizeHttpRequests { requests ->
                requests
                    .requestMatchers(HttpMethod.POST, "/expo")
                    .hasAuthority(ADMIN_AUTHORITY)
                    .requestMatchers(HttpMethod.GET, "/expo", "/expo/{expo_id}")
                    .hasAuthority(ADMIN_AUTHORITY)
                    .requestMatchers(HttpMethod.PATCH, "/expo/{expo_id}")
                    .hasAuthority(ADMIN_AUTHORITY)
                    .anyRequest()
                    .denyAll()
            }

        return http.build()
    }

    private fun writeError(
        response: HttpServletResponse,
        status: Int,
        message: String,
    ) {
        response.status = status
        response.characterEncoding = Charsets.UTF_8.name()
        response.contentType = MediaType.APPLICATION_JSON_VALUE
        response.writer.write("{\"status\":$status,\"message\":\"$message\"}")
    }

    private companion object {
        const val ADMIN_AUTHORITY = "ROLE_ADMIN"
    }
}
