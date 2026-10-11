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
import team.startup.expo.global.swagger.ApiError

@RestController
@RequestMapping("/internal/expo")
class InternalExpoController(
    private val getExpoPeriodService: GetExpoPeriodService,
    private val getTrainingProgramBatchService: GetTrainingProgramBatchService,
    private val getStandardProgramService: GetStandardProgramService,
) {
    @GetMapping("/{expo_id}")
    @Operation(summary = "내부 박람회 기간 조회")
    @ApiResponse(responseCode = "200", description = "처리 성공")
    @ApiError(status = 404, description = "요청한 박람회·프로그램·회차·이미지 또는 연동 대상이 없습니다.")
    fun getExpoPeriod(
        @PathVariable("expo_id") expoId: String,
    ): ResponseEntity<ExpoPeriodResponse> = ResponseEntity.ok(getExpoPeriodService.execute(expoId))

    @PostMapping("/{expo_id}/training-programs/batch")
    @Operation(summary = "내부 연수 프로그램 일괄 조회")
    @ApiResponse(responseCode = "200", description = "처리 성공")
    @ApiError(status = 400, description = "입력 값·요청 형식·기간·식별자가 올바르지 않습니다.")
    @ApiError(status = 404, description = "요청한 박람회·프로그램·회차·이미지 또는 연동 대상이 없습니다.")
    fun getTrainingPrograms(
        @PathVariable("expo_id") expoId: String,
        @Valid @RequestBody request: TrainingProgramBatchRequest,
    ): ResponseEntity<List<TrainingProgramResponse>> = ResponseEntity.ok(getTrainingProgramBatchService.execute(expoId, request))

    @GetMapping("/{expo_id}/standard-programs/{program_id}")
    @Operation(summary = "내부 일반 프로그램 조회")
    @ApiResponse(responseCode = "200", description = "처리 성공")
    @ApiError(status = 400, description = "입력 값·요청 형식·기간·식별자가 올바르지 않습니다.")
    @ApiError(status = 404, description = "요청한 박람회·프로그램·회차·이미지 또는 연동 대상이 없습니다.")
    fun getStandardProgram(
        @PathVariable("expo_id") expoId: String,
        @PathVariable("program_id") programId: Long,
    ): ResponseEntity<StandardProgramTitleResponse> = ResponseEntity.ok(getStandardProgramService.execute(expoId, programId))
}
