package team.startup.expo.global.swagger

import com.fasterxml.jackson.annotation.JsonFormat
import io.swagger.v3.core.converter.ModelConverters
import io.swagger.v3.core.util.Json
import io.swagger.v3.oas.models.Components
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.info.Info
import io.swagger.v3.oas.models.media.ArraySchema
import io.swagger.v3.oas.models.media.Content
import io.swagger.v3.oas.models.media.MediaType
import io.swagger.v3.oas.models.media.Schema
import io.swagger.v3.oas.models.responses.ApiResponse
import io.swagger.v3.oas.models.security.SecurityRequirement
import io.swagger.v3.oas.models.security.SecurityScheme
import io.swagger.v3.oas.models.servers.Server
import org.hibernate.validator.constraints.CodePointLength
import org.springdoc.core.customizers.OpenApiCustomizer
import org.springdoc.core.customizers.OperationCustomizer
import org.springdoc.core.customizers.PropertyCustomizer
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import team.startup.expo.domain.expo.presentation.dto.response.ExpoPageResponse
import team.startup.expo.global.exception.ErrorResponse

@Configuration
@ConditionalOnProperty(prefix = "swagger", name = ["enabled"], havingValue = "true")
class OpenApiConfig(
    @Value("\${swagger.include-internal:false}") private val includeInternal: Boolean,
) {
    @Bean
    fun expoOpenApi(): OpenAPI =
        OpenAPI()
            .servers(listOf(Server().url("/")))
            .info(
                Info()
                    .title("Expo Expo Server API")
                    .version("0.2.0")
                    .description(
                        "박람회·일반 프로그램·연수 프로그램·이미지 API입니다. " +
                            "관리자 API는 RS256 Bearer JWT와 ROLE_ADMIN이 필요합니다. " +
                            "JWT는 숫자 sub, 문자열 role, iat/exp 및 최대 15분 수명을 검증합니다. " +
                            "내부 API는 X-Internal-Token으로 인증합니다. " +
                            "오류 응답은 {status, message} 형식입니다. " +
                            "GET /expo는 page/size 생략 시 배열, 지정 시 페이지 객체를 반환합니다.",
                    ),
            ).components(
                Components().addSecuritySchemes(
                    "bearerAuth",
                    SecurityScheme().type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("JWT"),
                ),
            )

    @Bean
    fun apiErrors(): OperationCustomizer =
        OperationCustomizer { operation, handler ->
            handler.method.getAnnotationsByType(ApiError::class.java).groupBy { it.status }.forEach { (status, errors) ->
                operation.responses.addApiResponse(status.toString(), errorResponse(errors.joinToString(" / ") { it.description }))
            }
            operation.responses.addApiResponse("500", errorResponse("예상하지 못한 서버 오류"))
            operation
        }

    // swagger-core가 읽지 않는 요청 형식 제약을 스키마에 옮긴다
    @Bean
    fun requestFormatConstraints(): PropertyCustomizer =
        PropertyCustomizer { property, type ->
            type.ctxAnnotations?.forEach { annotation ->
                when (annotation) {
                    is CodePointLength -> {
                        property.maxLength(annotation.max)
                    }

                    // ponytail: 프로젝트에서 쓰는 비 ISO 일시 형식은 이것 하나뿐이다. 다른 pattern이 생기면 여기에 추가한다
                    is JsonFormat -> {
                        if (annotation.pattern == "yyyy-MM-dd HH:mm") {
                            property.format(null).pattern("^\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}$")
                        }
                    }
                }
            }
            property
        }

    @Bean
    fun expoDocumentation(): OpenApiCustomizer =
        OpenApiCustomizer { api ->
            val components = api.components
            ModelConverters.getInstance(true).readAll(ErrorResponse::class.java).forEach(components::addSchemas)
            ModelConverters.getInstance(true).readAll(ExpoPageResponse::class.java).forEach(components::addSchemas)
            api.paths["/expo"]
                ?.get
                ?.responses
                ?.get("200")
                ?.content
                ?.get("application/json")
                ?.schema =
                Schema<Any>()
                    .addOneOfItem(ArraySchema().items(Schema<Any>().`$ref`("#/components/schemas/ExpoSummaryResponse")))
                    .addOneOfItem(Schema<Any>().`$ref`("#/components/schemas/ExpoPageResponse"))
            if (includeInternal) {
                components.addSecuritySchemes(
                    "internalToken",
                    SecurityScheme()
                        .type(SecurityScheme.Type.APIKEY)
                        .`in`(SecurityScheme.In.HEADER)
                        .name("X-Internal-Token")
                        .description("내부 서비스 호출 인증. 실제 토큰은 문서에 기록하지 않습니다."),
                )
            } else {
                api.paths.keys.removeIf { it.startsWith("/internal/") }
            }
            api.paths.forEach { (path, item) ->
                item.readOperationsMap().forEach { (method, operation) ->
                    val internal = path.startsWith("/internal/")
                    val public =
                        (
                            method.name == "GET" &&
                                (path == "/image/{id}" || path.startsWith("/standard/program/") || path.startsWith("/training/program/"))
                        ) ||
                            (
                                method.name == "POST" &&
                                    (path.startsWith("/standard/application/") || path.startsWith("/training/application/"))
                            )
                    if (internal || !public) {
                        operation.addSecurityItem(SecurityRequirement().addList(if (internal) "internalToken" else "bearerAuth"))
                        operation.responses.addApiResponse(
                            "401",
                            errorResponse(if (internal) "내부 토큰 누락 또는 불일치" else "JWT 누락 또는 유효하지 않은 JWT"),
                        )
                        if (!internal) operation.responses.addApiResponse("403", errorResponse("ROLE_ADMIN 권한이 없거나 허용되지 않은 경로"))
                    }
                }
            }
            if (!includeInternal) {
                val schemas = components.schemas
                val reachable = mutableSetOf<String>()
                val pending = ArrayDeque(references(api.paths))
                while (pending.isNotEmpty()) {
                    val name = pending.removeFirst()
                    if (reachable.add(name)) schemas[name]?.let { pending += references(it) }
                }
                schemas.keys.retainAll(reachable)
            }
        }

    private fun references(value: Any): List<String> =
        Regex("#/components/schemas/([^\"]+)\"").findAll(Json.mapper().writeValueAsString(value)).map { it.groupValues[1] }.toList()

    private fun errorResponse(description: String): ApiResponse =
        ApiResponse().description(description).content(
            Content().addMediaType("application/json", MediaType().schema(Schema<Any>().`$ref`("#/components/schemas/ErrorResponse"))),
        )
}
