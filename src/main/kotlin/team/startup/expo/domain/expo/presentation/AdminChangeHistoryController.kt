package team.startup.expo.domain.expo.presentation

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.responses.ApiResponse
import jakarta.validation.Valid
import org.springframework.http.CacheControl
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.ModelAttribute
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RestController
import team.startup.expo.domain.expo.presentation.dto.request.AdminChangeHistoryRequest
import team.startup.expo.domain.expo.presentation.dto.response.AdminChangeHistoryPage
import team.startup.expo.domain.expo.service.GetAdminChangeHistoryService
import team.startup.expo.global.swagger.ApiError

@RestController
class AdminChangeHistoryController(
    private val history: GetAdminChangeHistoryService,
) {
    @Operation(
        summary = "행사별 모집·회차 변경 처리 이력",
        description =
            "현재 GLOBAL_ADMIN 조회 권한을 원본 JWT로 확인합니다. from 포함/to 제외 UTC 기간, type 필터. " +
                "occurredAt DESC, id DESC 정렬. 과거 이력은 추론하지 않으며 처리자 미기록은 adminId=null입니다.",
    )
    @GetMapping("/expo/{expo_id}/admin-change-history")
    @ApiResponse(responseCode = "200", description = "행사별 변경 이력 페이지")
    @ApiError(status = 400, description = "페이지·기간·유형이 올바르지 않습니다.")
    @ApiError(status = 401, description = "인증이 유효하지 않습니다.")
    @ApiError(status = 403, description = "현재 관리자 조회 권한이 없습니다.")
    @ApiError(status = 404, description = "박람회를 찾지 못 했습니다.")
    @ApiError(status = 503, description = "현재 권한을 확인할 수 없거나 삭제 중인 박람회입니다.")
    fun get(
        @PathVariable("expo_id") expoId: String,
        @RequestHeader("Authorization") authorization: String,
        @Valid @ModelAttribute request: AdminChangeHistoryRequest,
    ): ResponseEntity<AdminChangeHistoryPage> =
        ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(history.execute(expoId, authorization, request))
}
