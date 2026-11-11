package team.startup.expo.domain.expo.presentation

import io.swagger.v3.oas.annotations.Operation
import org.springframework.http.CacheControl
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import team.startup.expo.domain.expo.presentation.dto.response.AdminExpoAccessResponse
import team.startup.expo.domain.expo.service.ExpoPermission
import team.startup.expo.domain.expo.service.GetAdminExpoAccessService

@RestController
class InternalAdminExpoAccessController(
    private val access: GetAdminExpoAccessService,
) {
    @Operation(
        summary = "행사별 관리자·스태프 권한 검증",
        description = "X-Internal-Token과 원본 Authorization JWT가 모두 필요합니다. permission 기본값은 APPLICATION_READ입니다.",
    )
    @GetMapping("/internal/expo/{expo_id}/admin-access")
    fun getAccess(
        @PathVariable("expo_id") expoId: String,
        @RequestHeader("Authorization", required = false) authorization: String?,
        @RequestParam(defaultValue = "APPLICATION_READ") permission: ExpoPermission,
    ): ResponseEntity<AdminExpoAccessResponse> =
        ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(access.execute(expoId, authorization, permission))
}
