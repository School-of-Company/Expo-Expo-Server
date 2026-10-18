package team.startup.expo.domain.expo.presentation

import io.swagger.v3.oas.annotations.Operation
import org.springframework.http.CacheControl
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RestController
import team.startup.expo.domain.expo.presentation.dto.response.AdminApplicationPromoteAccessResponse
import team.startup.expo.domain.expo.service.GetAdminApplicationPromoteAccessService

@RestController
class InternalAdminApplicationPromoteAccessController(
    private val access: GetAdminApplicationPromoteAccessService,
) {
    @Operation(
        summary = "현재 관리자의 행사 APPLICATION_PROMOTE 검증",
        description =
            "자동 승급 종료 뒤 대기 신청 수동 확정용입니다. 내부 토큰, 원본 관리자 JWT, 현재 행사별 수동 확정 권한이 필요합니다. " +
                "APPLICATION_READ·APPLICATION_CANCEL은 수동 확정 권한이 아닙니다.",
    )
    @GetMapping("/internal/expo/{expo_id}/admin-application-promote-access")
    fun getAccess(
        @PathVariable("expo_id") expoId: String,
        @RequestHeader("Authorization", required = false) authorization: String?,
    ): ResponseEntity<AdminApplicationPromoteAccessResponse> =
        ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(access.execute(expoId, authorization))
}
