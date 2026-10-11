package team.startup.expo.domain.expo.presentation

import io.swagger.v3.oas.annotations.Operation
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import org.springframework.http.CacheControl
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.ModelAttribute
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RestController
import team.startup.expo.domain.expo.presentation.dto.request.ProgramSessionPageRequest
import team.startup.expo.domain.expo.presentation.dto.response.ProgramSessionPageResponse
import team.startup.expo.domain.expo.service.GetProgramSessionPageService
import team.startup.expo.global.exception.ExpectedException

@RestController
class InternalProgramSessionController(
    private val programs: GetProgramSessionPageService,
) {
    @Operation(summary = "행사의 현재 프로그램 묶음과 회차 조회", description = "서비스 전용, 최대 100개. 다음 페이지는 definitionVersion을 유지하며 변경 시 409로 다시 시작합니다.")
    @GetMapping("/internal/expo/{expo_id}/program-sessions")
    fun getPrograms(
        @PathVariable("expo_id") expoId: String,
        @Valid @ModelAttribute request: ProgramSessionPageRequest,
        servletRequest: HttpServletRequest,
    ): ResponseEntity<ProgramSessionPageResponse> {
        val allowed = setOf("programType", "programId", "groupId", "page", "size", "definitionVersion")
        if (servletRequest.parameterMap.any { (key, values) -> key !in allowed || values.size != 1 || values.single().isBlank() }) {
            throw ExpectedException(HttpStatus.BAD_REQUEST, "지원하지 않거나 중복 또는 빈 조회 조건입니다.")
        }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(programs.execute(expoId, request))
    }
}
