package team.startup.expo.swagger

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springdoc.webmvc.api.OpenApiWebMvcResource
import org.springdoc.webmvc.ui.SwaggerWelcomeWebMvc
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.persistence.autoconfigure.EntityScan
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.context.ApplicationContext
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.TestPropertySource
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import team.startup.expo.support.TestJwt
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "eureka.client.enabled=false",
        "EXPO_INTERNAL_TOKEN=swagger-test-only",
        "expo.standard-created.enabled=false",
        "expo.training.sms.enabled=false",
        "expo.training.sms.contract-verified=false",
    ],
)
@Testcontainers
@EntityScan("team.startup.expo.domain")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
abstract class SwaggerHttpSupport {
    @LocalServerPort
    private var port: Int = 0

    @Autowired
    protected lateinit var mapper: ObjectMapper

    @Autowired
    private lateinit var context: ApplicationContext

    protected fun request(
        path: String,
        method: String = "GET",
        role: String? = null,
        internalToken: String? = null,
    ): HttpResponse<String> {
        val builder = HttpRequest.newBuilder(URI.create("http://localhost:$port$path")).method(method, HttpRequest.BodyPublishers.noBody())
        role?.let { builder.header("Authorization", "Bearer ${TestJwt.token(it)}") }
        internalToken?.let { builder.header("X-Internal-Token", it) }
        return HttpClient.newHttpClient().send(builder.build(), HttpResponse.BodyHandlers.ofString())
    }

    protected fun document(): JsonNode {
        val response = request("/expo/v3/api-docs")
        response.statusCode() shouldBe 200
        response
            .headers()
            .firstValue("Content-Type")
            .orElse("")
            .startsWith("application/json") shouldBe true
        return mapper.readTree(response.body())
    }

    protected fun assertDisabled() {
        context.getBeansOfType(OpenApiWebMvcResource::class.java).isEmpty() shouldBe true
        context.getBeansOfType(SwaggerWelcomeWebMvc::class.java).isEmpty() shouldBe true
        listOf(
            "/expo/v3/api-docs",
            "/expo/v3/api-docs/swagger-config",
            "/expo/swagger-ui.html",
            "/expo/swagger-ui/index.html",
            "/expo/swagger-ui/swagger-ui.css",
        ).forEach {
            request(it).statusCode() shouldBe 401
            request(it, role = "ROLE_ADMIN").statusCode() shouldBe 403
        }
    }

    protected fun assertAuthenticationUnchanged() {
        request("/expo/sample-expo").statusCode() shouldBe 401
        request("/expo/sample-expo", role = "ROLE_USER").statusCode() shouldBe 403
        request("/expo/sample-expo", role = "ROLE_ADMIN").statusCode() shouldBe 404
        request("/expo/unknown/path", role = "ROLE_ADMIN").statusCode() shouldBe 403
        request("/internal/expo/sample-expo").statusCode() shouldBe 401
        request("/internal/expo/sample-expo", internalToken = "wrong-test-token").statusCode() shouldBe 401
        request("/internal/expo/sample-expo", internalToken = "swagger-test-only").statusCode() shouldBe 404
        request("/expo").statusCode() shouldBe 401
        request("/expo", role = "ROLE_ADMIN").statusCode() shouldBe 200
        request("/expo/valid", role = "ROLE_ADMIN").statusCode() shouldBe 200
        request("/standard/program/sample-expo").statusCode() shouldBe 404
        request("/training/program/sample-expo").statusCode() shouldBe 404
    }

    companion object {
        @Container
        @ServiceConnection
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:17-alpine")

        @DynamicPropertySource
        @JvmStatic
        fun jwt(registry: DynamicPropertyRegistry) {
            registry.add("JWT_PUBLIC_KEY") { TestJwt.publicKeyPem }
        }
    }
}

class SwaggerDisabledHttpTests : SwaggerHttpSupport() {
    @Test
    fun `기본 설정은 JSON과 UI를 모두 차단하고 기존 인증을 유지한다`() {
        assertDisabled()
        assertAuthenticationUnchanged()
    }
}

@TestPropertySource(properties = ["swagger.enabled=false", "swagger.include-internal=true"])
class SwaggerDisabledInternalHttpTests : SwaggerHttpSupport() {
    @Test
    fun `내부 문서 설정만으로 문서를 활성화하지 않는다`() {
        assertDisabled()
    }
}

@TestPropertySource(properties = ["swagger.enabled=true"])
class SwaggerEnabledHttpTests : SwaggerHttpSupport() {
    @Test
    fun `공개 문서에서 내부 경로와 내부 전용 스키마를 제거한다`() {
        val api = document()
        api.path("info").path("title").asString() shouldBe "Expo Expo Server API"
        api.path("info").path("version").asString() shouldBe "0.2.0"
        api.path("servers")[0].path("url").asString() shouldBe "/"
        val paths = api.path("paths")
        paths.propertyNames().none { it.startsWith("/internal/") } shouldBe true
        listOf(
            "ExpoPeriodResponse",
            "TrainingProgramBatchRequest",
            "StandardProgramTitleResponse",
            "ProgramFormChangeRequest",
            "ProgramFormChangeKind",
            "ProgramFormChangeResponse",
            "ProgramFormUsageResponse",
        ).forEach {
            api.path("components").path("schemas").has(it) shouldBe false
        }
        api.path("components").path("schemas").has("PreregisterSessionResponse") shouldBe true
        api.path("components").path("securitySchemes").has("internalToken") shouldBe false
        val schemas = api.path("components").path("schemas")
        Regex("#/components/schemas/([^\"]+)\"").findAll(api.toString()).forEach { schemas.has(it.groupValues[1]) shouldBe true }
        schemas
            .path("CreateExpoRequest")
            .path("properties")
            .path("title")
            .has("description") shouldBe true
        schemas
            .path("ApplyTrainingProgramRequest")
            .path("properties")
            .path("trainingId")
            .path("example")
            .asString() shouldBe
            "sample-training-code"
        schemas.path("ErrorResponse").path("properties").has("status") shouldBe true
        listOf("400", "401", "403", "404", "409", "502", "503", "500").forEach {
            paths
                .path("/expo/{expo_id}")
                .path("patch")
                .path("responses")
                .has(it) shouldBe true
        }
        paths
            .path("/expo/valid")
            .path("get")
            .path("responses")
            .has("502") shouldBe true
        paths
            .path("/expo")
            .path("post")
            .path("responses")
            .has("201") shouldBe true
        paths
            .path(
                "/expo",
            ).path("get")
            .path("responses")
            .path("200")
            .path("content")
            .path("application/json")
            .path("schema")
            .path("oneOf")
            .size() shouldBe
            2
        paths
            .path("/expo/{expo_id}")
            .path("patch")
            .path("responses")
            .has("204") shouldBe true
        paths.path("/standard/program/{expo_id}").path("get").has("security") shouldBe false
        paths
            .path("/expo/{expo_id}")
            .path("get")
            .path("security")[0]
            .has("bearerAuth") shouldBe true
    }

    @Test
    fun `UI 진입과 HTML에서 참조하는 모든 리소스는 expo 접두사로 응답한다`() {
        val redirect = request("/expo/swagger-ui.html")
        redirect.statusCode() shouldBe 302
        val location = redirect.headers().firstValue("Location").orElseThrow()
        location shouldBe "/expo/swagger-ui/index.html"
        val html = request(location)
        html.statusCode() shouldBe 200
        val assets =
            Regex(
                "(?:src|href)=\"([^\"]+)\"",
            ).findAll(html.body()).map { it.groupValues[1] }.filterNot { it.startsWith("#") }.toList()
        assets.isNotEmpty() shouldBe true
        assets.forEach {
            val assetPath = URI.create("http://localhost$location").resolve(it).path
            assetPath.startsWith("/expo/swagger-ui/") shouldBe true
            request(assetPath).statusCode() shouldBe 200
        }
        request("/expo/swagger-ui/swagger-initializer.js").body().contains("/expo/v3/api-docs/swagger-config") shouldBe true
        request("/expo/swagger-ui/oauth2-redirect.html").statusCode() shouldBe 200
        val config = mapper.readTree(request("/expo/v3/api-docs/swagger-config").body())
        config.path("url").asString() shouldBe "/expo/v3/api-docs"
        config.path("oauth2RedirectUrl").asString().endsWith("/expo/swagger-ui/oauth2-redirect.html") shouldBe true
    }

    @Test
    fun `문서 GET만 허용하고 루트 문서 경로와 기존 API 인증은 유지한다`() {
        listOf("/v3/api-docs", "/swagger-ui/index.html", "/swagger-ui.html").forEach {
            request(it).statusCode() shouldBe 401
            request(it, role = "ROLE_ADMIN").statusCode() shouldBe 403
        }
        listOf("POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS").forEach { method ->
            listOf("/expo/v3/api-docs", "/expo/swagger-ui.html", "/expo/swagger-ui/swagger-ui.css").forEach {
                request(it, method).statusCode() shouldBe 401
                request(it, method, role = "ROLE_ADMIN").statusCode() shouldBe 403
            }
        }
        assertAuthenticationUnchanged()
    }
}

@TestPropertySource(properties = ["swagger.enabled=true", "swagger.include-internal=true"])
class SwaggerIncludeInternalHttpTests : SwaggerHttpSupport() {
    @Test
    fun `내부 포함 시 내부 전용 스키마와 토큰 인증을 문서화한다`() {
        val api = document()
        val scheme = api.path("components").path("securitySchemes").path("internalToken")
        scheme.path("type").asString() shouldBe "apiKey"
        scheme.path("in").asString() shouldBe "header"
        scheme.path("name").asString() shouldBe "X-Internal-Token"
        scheme.has("example") shouldBe false
        api.path("components").path("schemas").has("ExpoPeriodResponse") shouldBe true
        api.path("components").path("schemas").has("ProgramFormChangeResponse") shouldBe true
        val batchResponses =
            api
                .path("paths")
                .path("/internal/expo/{expo_id}/training-programs/batch")
                .path("post")
                .path("responses")
        batchResponses.has("200") shouldBe true
        batchResponses.has("201") shouldBe false
        val internalPaths = api.path("paths").properties().filter { it.key.startsWith("/internal/") }
        internalPaths.isNotEmpty() shouldBe true
        internalPaths.forEach { (_, item) ->
            item.properties().filter { it.key in listOf("get", "post", "put", "delete") }.forEach { (_, operation) ->
                operation.path("security")[0].has("internalToken") shouldBe true
                operation.path("security")[0].has("bearerAuth") shouldBe false
                operation.path("responses").has("401") shouldBe true
            }
        }
        assertAuthenticationUnchanged()
    }
}
