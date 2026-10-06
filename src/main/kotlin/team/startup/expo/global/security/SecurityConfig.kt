package team.startup.expo.global.security

import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator
import org.springframework.security.oauth2.core.OAuth2Error
import org.springframework.security.oauth2.core.OAuth2TokenValidator
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.oauth2.jwt.JwtValidators
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter
import team.startup.expo.global.exception.ErrorResponse
import tools.jackson.databind.ObjectMapper
import java.security.KeyFactory
import java.security.interfaces.RSAPublicKey
import java.security.spec.X509EncodedKeySpec
import java.time.Duration
import java.time.Instant
import java.util.Base64

@Configuration
@EnableWebSecurity
class SecurityConfig {
    @Bean
    fun jwtDecoder(
        @Value("\${JWT_PUBLIC_KEY}") publicKeyPem: String,
    ): JwtDecoder {
        val pem = publicKeyPem.replace("\\n", "\n").replace("-----BEGIN PUBLIC KEY-----", "").replace("-----END PUBLIC KEY-----", "")
        val key = KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(Base64.getMimeDecoder().decode(pem))) as RSAPublicKey
        return NimbusJwtDecoder.withPublicKey(key).signatureAlgorithm(SignatureAlgorithm.RS256).build().apply {
            setJwtValidator(
                DelegatingOAuth2TokenValidator(
                    JwtValidators.createDefault(),
                    OAuth2TokenValidator<Jwt> { jwt ->
                        val issuedAt = jwt.issuedAt
                        val expiresAt = jwt.expiresAt
                        if (
                            jwt.subject?.toLongOrNull() != null &&
                            issuedAt != null &&
                            expiresAt != null &&
                            Duration.between(issuedAt, expiresAt) > Duration.ZERO &&
                            Duration.between(issuedAt, expiresAt) <= Duration.ofMinutes(15) &&
                            !issuedAt.isAfter(Instant.now().plusSeconds(30)) &&
                            expiresAt.isAfter(Instant.now()) &&
                            jwt.getClaim<Any>("role") is String
                        ) {
                            OAuth2TokenValidatorResult.success()
                        } else {
                            OAuth2TokenValidatorResult.failure(OAuth2Error("invalid_token"))
                        }
                    },
                ),
            )
        }
    }

    @Bean
    fun securityFilterChain(
        http: HttpSecurity,
        objectMapper: ObjectMapper,
        @Value("\${EXPO_INTERNAL_TOKEN:}") internalToken: String,
    ): SecurityFilterChain {
        if (internalToken.isBlank()) {
            logger.warn("EXPO_INTERNAL_TOKEN is not configured; internal expo requests will be rejected")
        }

        http
            .csrf { it.disable() }
            .cors { it.disable() }
            .formLogin { it.disable() }
            .httpBasic { it.disable() }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .exceptionHandling { exceptions ->
                exceptions
                    .authenticationEntryPoint { _, response, _ ->
                        writeError(objectMapper, response, HttpServletResponse.SC_UNAUTHORIZED, "인증이 필요합니다.")
                    }.accessDeniedHandler { _, response, _ ->
                        writeError(objectMapper, response, HttpServletResponse.SC_FORBIDDEN, "접근 권한이 없습니다.")
                    }
            }.authorizeHttpRequests { requests ->
                requests
                    .requestMatchers(HttpMethod.GET, "/actuator/health", "/actuator/health/**", "/actuator/prometheus")
                    .permitAll()
                    .requestMatchers(HttpMethod.GET, "/image/{id}")
                    .permitAll()
                    .requestMatchers(HttpMethod.POST, "/image")
                    .hasAuthority(ADMIN_AUTHORITY)
                    .requestMatchers(HttpMethod.GET, "/internal/expo/{expo_id}")
                    .hasAuthority(InternalTokenFilter.INTERNAL_AUTHORITY)
                    .requestMatchers(HttpMethod.POST, "/expo")
                    .hasAuthority(ADMIN_AUTHORITY)
                    .requestMatchers(HttpMethod.GET, "/expo", "/expo/{expo_id}")
                    .hasAuthority(ADMIN_AUTHORITY)
                    .requestMatchers(HttpMethod.GET, "/standard/program/{expo_id}", "/training/program/{expo_id}")
                    .hasAuthority(ADMIN_AUTHORITY)
                    .requestMatchers(HttpMethod.POST, "/standard/application/{expo_id}")
                    .permitAll()
                    .requestMatchers(HttpMethod.POST, "/standard/{expo_id}", "/standard/list/{expo_id}")
                    .hasAuthority(ADMIN_AUTHORITY)
                    .requestMatchers(HttpMethod.PATCH, "/standard/{standardPro_id}")
                    .hasAuthority(ADMIN_AUTHORITY)
                    .requestMatchers(HttpMethod.GET, "/standard/{standardPro_id}")
                    .hasAuthority(ADMIN_AUTHORITY)
                    .requestMatchers(HttpMethod.DELETE, "/standard/{standardPro_id}")
                    .hasAuthority(ADMIN_AUTHORITY)
                    .requestMatchers(HttpMethod.POST, "/training/{expo_id}", "/training/list/{expo_id}")
                    .hasAuthority(ADMIN_AUTHORITY)
                    .requestMatchers(HttpMethod.PATCH, "/training/{trainingPro_id}")
                    .hasAuthority(ADMIN_AUTHORITY)
                    .requestMatchers(HttpMethod.GET, "/training/{trainingPro_id}")
                    .hasAuthority(ADMIN_AUTHORITY)
                    .requestMatchers(HttpMethod.DELETE, "/training/{trainingPro_id}")
                    .hasAuthority(ADMIN_AUTHORITY)
                    .requestMatchers(HttpMethod.POST, "/training/application/{trainingPro_id}")
                    .denyAll()
                    .requestMatchers(HttpMethod.PATCH, "/expo/{expo_id}")
                    .hasAuthority(ADMIN_AUTHORITY)
                    .requestMatchers(HttpMethod.DELETE, "/expo/{expo_id}")
                    .hasAuthority(ADMIN_AUTHORITY)
                    .anyRequest()
                    .denyAll()
            }

        val jwtConverter =
            JwtAuthenticationConverter().apply {
                setJwtGrantedAuthoritiesConverter { jwt ->
                    listOf(SimpleGrantedAuthority(requireNotNull(jwt.getClaimAsString("role"))))
                }
            }
        http.oauth2ResourceServer { resource ->
            resource.jwt { it.jwtAuthenticationConverter(jwtConverter) }
            resource.authenticationEntryPoint { _, response, _ ->
                writeError(objectMapper, response, HttpServletResponse.SC_UNAUTHORIZED, "인증이 필요합니다.")
            }
        }

        http.addFilterBefore(InternalTokenFilter(internalToken, objectMapper), UsernamePasswordAuthenticationFilter::class.java)

        return http.build()
    }

    private fun writeError(
        objectMapper: ObjectMapper,
        response: HttpServletResponse,
        status: Int,
        message: String,
    ) {
        response.status = status
        response.characterEncoding = Charsets.UTF_8.name()
        response.contentType = MediaType.APPLICATION_JSON_VALUE
        objectMapper.writeValue(response.writer, ErrorResponse(status = status, message = message))
    }

    private companion object {
        const val ADMIN_AUTHORITY = "ROLE_ADMIN"
        val logger = LoggerFactory.getLogger(SecurityConfig::class.java)
    }
}
