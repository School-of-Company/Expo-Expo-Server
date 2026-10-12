package team.startup.expo.domain.expo.presentation

import io.swagger.v3.oas.annotations.Operation
import org.springframework.http.CacheControl
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RestController
import team.startup.expo.domain.expo.presentation.dto.response.AdminApplicationCancelAccessResponse
import team.startup.expo.domain.expo.service.GetAdminApplicationCancelAccessService

@RestController
class InternalAdminApplicationCancelAccessController(
    private val access: GetAdminApplicationCancelAccessService,
) {
    @Operation(
        summary = "현재 관리자의 행사 APPLICATION_CANCEL 검증",
        description = "내부 토큰, 원본 관리자 JWT, 현재 행사별 취소 권한이 필요합니다. APPLICATION_READ는 취소 권한이 아닙니다.",
    )
    @GetMapping("/internal/expo/{expo_id}/admin-application-cancel-access")
    fun getAccess(
        @PathVariable("expo_id") expoId: String,
        @RequestHeader("Authorization", required = false) authorization: String?,
    ): ResponseEntity<AdminApplicationCancelAccessResponse> =
        ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(access.execute(expoId, authorization))
}
