package team.startup.expo.domain.expo.presentation

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.responses.ApiResponse
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import team.startup.expo.domain.expo.presentation.dto.response.ExpoPeriodResponse
import team.startup.expo.domain.expo.service.GetExpoPeriodService
import team.startup.expo.domain.standard.presentation.dto.response.StandardProgramTitleResponse
import team.startup.expo.domain.standard.service.GetStandardProgramService
import team.startup.expo.domain.training.presentation.dto.request.TrainingProgramBatchRequest
import team.startup.expo.domain.training.presentation.dto.response.TrainingProgramResponse
import team.startup.expo.domain.training.service.GetTrainingProgramBatchService

@RestController
@RequestMapping("/internal/expo")
class InternalExpoController(
    private val getExpoPeriodService: GetExpoPeriodService,
    private val getTrainingProgramBatchService: GetTrainingProgramBatchService,
    private val getStandardProgramService: GetStandardProgramService,
) {
    @Operation(
        summary = "박람회 기간 및 참가자 전화번호 인증 설정 조회",
        description = "Expo에 저장된 전화번호 인증 설정과 revision을 반환합니다. 조회 실패를 false로 처리하지 않습니다.",
    )
    @ApiResponse(responseCode = "200", description = "현재 설정과 revision. 기존 박람회 기본값은 false, 0입니다.")
    @ApiResponse(responseCode = "401", description = "내부 토큰 누락 또는 불일치")
    @ApiResponse(responseCode = "404", description = "박람회 없음")
    @ApiResponse(responseCode = "409", description = "삭제 또는 변경 중인 박람회")
    @GetMapping("/{expo_id}")
    fun getExpoPeriod(
        @PathVariable("expo_id") expoId: String,
    ): ResponseEntity<ExpoPeriodResponse> = ResponseEntity.ok(getExpoPeriodService.execute(expoId))

    @PostMapping("/{expo_id}/training-programs/batch")
    fun getTrainingPrograms(
        @PathVariable("expo_id") expoId: String,
        @Valid @RequestBody request: TrainingProgramBatchRequest,
    ): ResponseEntity<List<TrainingProgramResponse>> = ResponseEntity.ok(getTrainingProgramBatchService.execute(expoId, request))

    @GetMapping("/{expo_id}/standard-programs/{program_id}")
    fun getStandardProgram(
        @PathVariable("expo_id") expoId: String,
        @PathVariable("program_id") programId: Long,
    ): ResponseEntity<StandardProgramTitleResponse> = ResponseEntity.ok(getStandardProgramService.execute(expoId, programId))
}
