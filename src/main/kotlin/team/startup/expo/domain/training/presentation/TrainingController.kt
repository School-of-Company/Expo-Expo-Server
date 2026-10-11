package team.startup.expo.domain.training.presentation

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.responses.ApiResponse
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import team.startup.expo.domain.training.presentation.dto.request.AddTrainingProgramRequest
import team.startup.expo.domain.training.presentation.dto.request.ApplyTrainingProgramRequest
import team.startup.expo.domain.training.presentation.dto.request.ApplyTrainingProgramsRequest
import team.startup.expo.domain.training.presentation.dto.request.ApplyTrainingProgramsWithTraineeRequest
import team.startup.expo.domain.training.presentation.dto.request.UpdateTrainingProgramRequest
import team.startup.expo.domain.training.presentation.dto.response.TrainingProgramResponse
import team.startup.expo.domain.training.presentation.dto.response.TrainingProgramTraineeResponse
import team.startup.expo.domain.training.service.ApplyTrainingProgramListService
import team.startup.expo.domain.training.service.ApplyTrainingProgramService
import team.startup.expo.domain.training.service.ApplyTrainingProgramsWithTraineeService
import team.startup.expo.domain.training.service.CreateTrainingProgramListService
import team.startup.expo.domain.training.service.CreateTrainingProgramService
import team.startup.expo.domain.training.service.DeleteTrainingProgramService
import team.startup.expo.domain.training.service.GetTrainingProgramListService
import team.startup.expo.domain.training.service.GetTrainingProgramTraineesService
import team.startup.expo.domain.training.service.UpdateTrainingProgramService
import team.startup.expo.global.swagger.ApiError

@RestController
@RequestMapping("/training")
class TrainingController(
    private val getTrainingProgramListService: GetTrainingProgramListService,
    private val createTrainingProgramService: CreateTrainingProgramService,
    private val createTrainingProgramListService: CreateTrainingProgramListService,
    private val updateTrainingProgramService: UpdateTrainingProgramService,
    private val getTrainingProgramTraineesService: GetTrainingProgramTraineesService,
    private val deleteTrainingProgramService: DeleteTrainingProgramService,
    private val applyTrainingProgramService: ApplyTrainingProgramService,
    private val applyTrainingProgramListService: ApplyTrainingProgramListService,
    private val applyTrainingProgramsWithTraineeService: ApplyTrainingProgramsWithTraineeService,
) {
    @Operation(summary = "연수 프로그램 목록 조회")
    @GetMapping("/program/{expo_id}")
    @ApiResponse(responseCode = "200", description = "처리 성공")
    @ApiError(status = 404, description = "요청한 박람회·프로그램·회차·이미지 또는 연동 대상이 없습니다.")
    fun getProgramList(
        @PathVariable("expo_id") expoId: String,
    ): ResponseEntity<List<TrainingProgramResponse>> = ResponseEntity.ok(getTrainingProgramListService.execute(expoId))

    @PostMapping("/{expo_id}")
    @Operation(summary = "연수 프로그램 생성")
    @ApiResponse(responseCode = "201", description = "처리 성공")
    @ApiError(status = 400, description = "입력 값·요청 형식·기간·식별자가 올바르지 않습니다.")
    @ApiError(status = 404, description = "요청한 박람회·프로그램·회차·이미지 또는 연동 대상이 없습니다.")
    @ApiError(status = 409, description = "삭제·변경 진행 상태, 버전, 신청 이력, 정원 또는 이미지 연결이 충돌합니다.")
    @ApiError(status = 502, description = "연동 서비스 응답 상태 또는 본문이 올바르지 않습니다.")
    @ApiError(status = 503, description = "연동 설정이 없거나 서비스·이미지 저장소에 연결할 수 없습니다.")
    fun addProgram(
        @PathVariable("expo_id") expoId: String,
        @Valid @RequestBody request: AddTrainingProgramRequest,
    ): ResponseEntity<Void> {
        createTrainingProgramService.execute(expoId, request)
        return ResponseEntity.status(HttpStatus.CREATED).build()
    }

    @PostMapping("/list/{expo_id}")
    @Operation(summary = "연수 프로그램 일괄 생성")
    @ApiResponse(responseCode = "201", description = "처리 성공")
    @ApiError(status = 400, description = "입력 값·요청 형식·기간·식별자가 올바르지 않습니다.")
    @ApiError(status = 404, description = "요청한 박람회·프로그램·회차·이미지 또는 연동 대상이 없습니다.")
    @ApiError(status = 409, description = "삭제·변경 진행 상태, 버전, 신청 이력, 정원 또는 이미지 연결이 충돌합니다.")
    @ApiError(status = 502, description = "연동 서비스 응답 상태 또는 본문이 올바르지 않습니다.")
    @ApiError(status = 503, description = "연동 설정이 없거나 서비스·이미지 저장소에 연결할 수 없습니다.")
    fun addPrograms(
        @PathVariable("expo_id") expoId: String,
        @Valid @RequestBody requests: List<AddTrainingProgramRequest>,
    ): ResponseEntity<Void> {
        createTrainingProgramListService.execute(expoId, requests)
        return ResponseEntity.status(HttpStatus.CREATED).build()
    }

    @PatchMapping("/{trainingPro_id}")
    @Operation(summary = "연수 프로그램 수정")
    @ApiResponse(responseCode = "204", description = "처리 완료 (본문 없음)")
    @ApiError(status = 400, description = "입력 값·요청 형식·기간·식별자가 올바르지 않습니다.")
    @ApiError(status = 404, description = "요청한 박람회·프로그램·회차·이미지 또는 연동 대상이 없습니다.")
    @ApiError(status = 409, description = "삭제·변경 진행 상태, 버전, 신청 이력, 정원 또는 이미지 연결이 충돌합니다.")
    @ApiError(status = 502, description = "연동 서비스 응답 상태 또는 본문이 올바르지 않습니다.")
    @ApiError(status = 503, description = "연동 설정이 없거나 서비스·이미지 저장소에 연결할 수 없습니다.")
    fun updateProgram(
        @PathVariable("trainingPro_id") trainingProgramId: Long,
        @Valid @RequestBody request: UpdateTrainingProgramRequest,
    ): ResponseEntity<Void> {
        updateTrainingProgramService.execute(trainingProgramId, request)
        return ResponseEntity.noContent().build()
    }

    @GetMapping("/{trainingPro_id}")
    @Operation(summary = "연수 프로그램 신청자·출석 조회")
    @ApiResponse(responseCode = "200", description = "처리 성공")
    @ApiError(status = 400, description = "입력 값·요청 형식·기간·식별자가 올바르지 않습니다.")
    @ApiError(status = 404, description = "요청한 박람회·프로그램·회차·이미지 또는 연동 대상이 없습니다.")
    @ApiError(status = 502, description = "연동 서비스 응답 상태 또는 본문이 올바르지 않습니다.")
    @ApiError(status = 503, description = "연동 설정이 없거나 서비스·이미지 저장소에 연결할 수 없습니다.")
    fun trainees(
        @PathVariable("trainingPro_id") programId: Long,
    ): ResponseEntity<List<TrainingProgramTraineeResponse>> = ResponseEntity.ok(getTrainingProgramTraineesService.execute(programId))

    @DeleteMapping("/{trainingPro_id}")
    @Operation(summary = "연수 프로그램 삭제")
    @ApiResponse(responseCode = "204", description = "처리 완료 (본문 없음)")
    @ApiError(status = 400, description = "입력 값·요청 형식·기간·식별자가 올바르지 않습니다.")
    @ApiError(status = 404, description = "요청한 박람회·프로그램·회차·이미지 또는 연동 대상이 없습니다.")
    @ApiError(status = 409, description = "삭제·변경 진행 상태, 버전, 신청 이력, 정원 또는 이미지 연결이 충돌합니다.")
    @ApiError(status = 502, description = "연동 서비스 응답 상태 또는 본문이 올바르지 않습니다.")
    @ApiError(status = 503, description = "연동 설정이 없거나 서비스·이미지 저장소에 연결할 수 없습니다.")
    fun deleteProgram(
        @PathVariable("trainingPro_id") programId: Long,
    ): ResponseEntity<Void> {
        deleteTrainingProgramService.execute(programId)
        return ResponseEntity.noContent().build()
    }

    @PostMapping("/application/{trainingPro_id}")
    @Operation(summary = "연수 프로그램 신청")
    @ApiResponse(responseCode = "201", description = "처리 성공")
    @ApiError(status = 400, description = "입력 값·요청 형식·기간·식별자가 올바르지 않습니다.")
    @ApiError(status = 404, description = "요청한 박람회·프로그램·회차·이미지 또는 연동 대상이 없습니다.")
    @ApiError(status = 409, description = "삭제·변경 진행 상태, 버전, 신청 이력, 정원 또는 이미지 연결이 충돌합니다.")
    @ApiError(status = 502, description = "연동 서비스 응답 상태 또는 본문이 올바르지 않습니다.")
    @ApiError(status = 503, description = "연동 설정이 없거나 서비스·이미지 저장소에 연결할 수 없습니다.")
    fun apply(
        @PathVariable("trainingPro_id") programId: Long,
        @Valid @RequestBody request: ApplyTrainingProgramRequest,
    ): ResponseEntity<Void> {
        applyTrainingProgramService.execute(programId, request)
        return ResponseEntity.status(HttpStatus.CREATED).build()
    }

    @PostMapping("/application/list")
    @Operation(summary = "연수 프로그램 일괄 신청")
    @ApiResponse(responseCode = "201", description = "처리 성공")
    @ApiError(status = 400, description = "입력 값·요청 형식·기간·식별자가 올바르지 않습니다.")
    @ApiError(status = 404, description = "요청한 박람회·프로그램·회차·이미지 또는 연동 대상이 없습니다.")
    @ApiError(status = 409, description = "삭제·변경 진행 상태, 버전, 신청 이력, 정원 또는 이미지 연결이 충돌합니다.")
    @ApiError(status = 502, description = "연동 서비스 응답 상태 또는 본문이 올바르지 않습니다.")
    @ApiError(status = 503, description = "연동 설정이 없거나 서비스·이미지 저장소에 연결할 수 없습니다.")
    fun applyList(
        @Valid @RequestBody request: ApplyTrainingProgramsRequest,
    ): ResponseEntity<Void> {
        applyTrainingProgramListService.execute(request)
        return ResponseEntity.status(HttpStatus.CREATED).build()
    }

    @PostMapping("/application/list/trainee/{expo_id}")
    @Operation(summary = "연수자 등록 및 연수 신청 교체")
    @ApiResponse(responseCode = "201", description = "처리 성공")
    @ApiError(status = 400, description = "입력 값·요청 형식·기간·식별자가 올바르지 않습니다.")
    @ApiError(status = 404, description = "요청한 박람회·프로그램·회차·이미지 또는 연동 대상이 없습니다.")
    @ApiError(status = 409, description = "삭제·변경 진행 상태, 버전, 신청 이력, 정원 또는 이미지 연결이 충돌합니다.")
    @ApiError(status = 502, description = "연동 서비스 응답 상태 또는 본문이 올바르지 않습니다.")
    @ApiError(status = 503, description = "연동 설정이 없거나 서비스·이미지 저장소에 연결할 수 없습니다.")
    fun applyListWithTrainee(
        @PathVariable("expo_id") expoId: String,
        @Valid @RequestBody request: ApplyTrainingProgramsWithTraineeRequest,
    ): ResponseEntity<Void> {
        applyTrainingProgramsWithTraineeService.execute(expoId, request)
        return ResponseEntity.status(HttpStatus.CREATED).build()
    }
}
