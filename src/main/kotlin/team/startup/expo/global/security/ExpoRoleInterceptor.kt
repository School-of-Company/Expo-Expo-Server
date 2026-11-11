package team.startup.expo.global.security

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.method.HandlerMethod
import org.springframework.web.servlet.HandlerInterceptor
import org.springframework.web.servlet.HandlerMapping
import org.springframework.web.servlet.config.annotation.InterceptorRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer
import team.startup.expo.domain.expo.service.ExpoAccess
import team.startup.expo.domain.expo.service.ExpoRoleSupport
import team.startup.expo.domain.standard.repository.StandardProgramRepository
import team.startup.expo.domain.training.repository.TrainingProgramRepository
import team.startup.expo.global.exception.ExpectedException

@Component
class ExpoRoleInterceptor(
    private val roles: ExpoRoleSupport,
    private val standards: StandardProgramRepository,
    private val trainings: TrainingProgramRepository,
) : HandlerInterceptor,
    WebMvcConfigurer {
    override fun addInterceptors(registry: InterceptorRegistry) {
        registry.addInterceptor(this).addPathPatterns("/expo/**", "/standard/**", "/training/**")
    }

    override fun preHandle(
        request: HttpServletRequest,
        response: HttpServletResponse,
        handler: Any,
    ): Boolean {
        if (!roles.enabled) return true
        if (handler !is HandlerMethod || !handler.beanType.packageName.startsWith("team.startup.expo.domain.")) return true
        val pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE) as? String ?: return true
        if (ApiPaths.isPublicInDocumentation(request.method, pattern) || pattern in ApiPaths.publicExpoGetPaths) return true
        @Suppress("UNCHECKED_CAST")
        val variables = request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE) as? Map<String, String> ?: emptyMap()
        val programRoute = pattern == "/standard/{standardPro_id}" || pattern == "/training/{trainingPro_id}"
        val expoId =
            if (programRoute) {
                val programId =
                    (variables["standardPro_id"] ?: variables["trainingPro_id"])?.toLongOrNull()
                        ?: throw ExpectedException(HttpStatus.BAD_REQUEST, "프로그램 ID가 올바르지 않습니다.")
                (if (variables.containsKey("standardPro_id")) standards.findExpoIdById(programId) else trainings.findExpoIdById(programId))
                    ?: throw ExpectedException(HttpStatus.NOT_FOUND, "프로그램을 찾지 못 했습니다.")
            } else {
                variables["expo_id"]
            }
        if (expoId != null) {
            val access =
                when {
                    pattern.contains("/members") || pattern.contains("/staff-invites") -> ExpoAccess.OWNER
                    pattern == "/expo/{expo_id}" && request.method == "DELETE" -> ExpoAccess.OWNER
                    programRoute || request.method != "GET" -> ExpoAccess.WRITE
                    else -> ExpoAccess.READ
                }
            roles.require(expoId, access)
        } else {
            roles.current()
        }
        return true
    }
}
