package team.startup.expo.domain.expo.presentation

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.ExampleObject
import io.swagger.v3.oas.annotations.responses.ApiResponse
import jakarta.validation.Valid
import org.springdoc.core.annotations.ParameterObject
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.ModelAttribute
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import team.startup.expo.domain.expo.presentation.dto.request.CreateExpoRequest
import team.startup.expo.domain.expo.presentation.dto.request.ExpoPageRequest
import team.startup.expo.domain.expo.presentation.dto.request.UpdateExpoRequest
import team.startup.expo.domain.expo.presentation.dto.request.UpdateParticipantPhoneVerificationRequest
import team.startup.expo.domain.expo.presentation.dto.response.CreateExpoResponse
import team.startup.expo.domain.expo.presentation.dto.response.ExpoDetailResponse
import team.startup.expo.domain.expo.presentation.dto.response.ExpoValidationResponse
import team.startup.expo.domain.expo.presentation.dto.response.ParticipantPhoneVerificationResponse
import team.startup.expo.domain.expo.presentation.dto.response.PublicExpoResponse
import team.startup.expo.domain.expo.service.CreateExpoService
import team.startup.expo.domain.expo.service.DeleteExpoService
import team.startup.expo.domain.expo.service.GetExpoDetailService
import team.startup.expo.domain.expo.service.GetExpoListService
import team.startup.expo.domain.expo.service.GetExpoValidationService
import team.startup.expo.domain.expo.service.GetPublicExpoService
import team.startup.expo.domain.expo.service.UpdateExpoService
import team.startup.expo.domain.expo.service.UpdateParticipantPhoneVerificationService
import team.startup.expo.global.swagger.ApiConflictError
import team.startup.expo.global.swagger.ApiInputError
import team.startup.expo.global.swagger.ApiIntegrationErrors
import team.startup.expo.global.swagger.ApiMutationErrors
import team.startup.expo.global.swagger.ApiNotFoundError

@RestController
@RequestMapping("/expo")
class ExpoController(
    private val createExpoService: CreateExpoService,
    private val getExpoListService: GetExpoListService,
    private val getExpoDetailService: GetExpoDetailService,
    private val getPublicExpoService: GetPublicExpoService,
    private val updateExpoService: UpdateExpoService,
    private val deleteExpoService: DeleteExpoService,
    private val getExpoValidationService: GetExpoValidationService,
    private val updateParticipantPhoneVerificationService: UpdateParticipantPhoneVerificationService,
) {
    @PatchMapping("/{expo_id}/participant-phone-verification")
    @Operation(summary = "참가자 전화번호 인증 설정 변경", description = "실제 값이 바뀔 때만 revision을 증가시킵니다.")
    @ApiResponse(responseCode = "200", description = "처리 성공")
    @ApiInputError
    @ApiNotFoundError
    @ApiConflictError
    fun updateParticipantPhoneVerification(
        @PathVariable("expo_id") expoId: String,
        @Valid @RequestBody request: UpdateParticipantPhoneVerificationRequest,
    ): ResponseEntity<ParticipantPhoneVerificationResponse> =
        ResponseEntity.ok(updateParticipantPhoneVerificationService.execute(expoId, request))

    @PostMapping
    @Operation(summary = "박람회 생성")
    @ApiResponse(responseCode = "201", description = "처리 성공")
    @ApiMutationErrors
    fun createExpo(
        @Valid @RequestBody request: CreateExpoRequest,
    ): ResponseEntity<CreateExpoResponse> =
        ResponseEntity
            .status(HttpStatus.CREATED)
            .body(createExpoService.execute(request))

    @Operation(summary = "박람회 목록 조회", description = "page 또는 size를 보내면 페이지 객체를, 둘 다 생략하면 기존 배열을 반환합니다.")
    @ApiResponse(
        responseCode = "200",
        content = [
            Content(
                mediaType = "application/json",
                examples = [
                    ExampleObject(name = "기존 목록", value = "[]"),
                    ExampleObject(
                        name = "페이지 목록",
                        value = """{"content":[],"page":0,"size":20,"totalElements":0,"totalPages":0,"hasNext":false}""",
                    ),
                ],
            ),
        ],
    )
    @GetMapping
    @ApiInputError
    fun getExpoList(
        @ParameterObject @Valid @ModelAttribute request: ExpoPageRequest,
    ): ResponseEntity<Any> = ResponseEntity.ok(getExpoListService.execute(request))

    @GetMapping("/{expo_id}")
    @Operation(summary = "박람회 상세 조회")
    @ApiResponse(responseCode = "200", description = "처리 성공")
    @ApiNotFoundError
    fun getExpoDetail(
        @PathVariable("expo_id") expoId: String,
    ): ResponseEntity<ExpoDetailResponse> = ResponseEntity.ok(getExpoDetailService.execute(expoId))

    @GetMapping("/{expo_id}/public")
    @Operation(summary = "참가자용 박람회 공개 조회", description = "로그인 없이 조회하며 제목·설명·장소·시작일·종료일과 참가자 전화번호 인증 필요 여부만 반환합니다.")
    @ApiResponse(responseCode = "200", description = "처리 성공")
    @ApiNotFoundError
    fun getPublicExpo(
        @PathVariable("expo_id") expoId: String,
    ): ResponseEntity<PublicExpoResponse> = ResponseEntity.ok(getPublicExpoService.execute(expoId))

    @GetMapping("/valid")
    @Operation(summary = "박람회 폼·설문 생성 여부 조회")
    @ApiResponse(responseCode = "200", description = "처리 성공")
    @ApiIntegrationErrors
    fun getExpoValidation(): ResponseEntity<ExpoValidationResponse> = ResponseEntity.ok(getExpoValidationService.execute())

    @Operation(
        summary = "박람회 수정",
        description = "모든 필드를 필수로 받아 전체 교체합니다. 목록에서 빠진 기존 프로그램은 삭제합니다.",
    )
    @PatchMapping("/{expo_id}")
    @ApiResponse(responseCode = "204", description = "처리 완료 (본문 없음)")
    @ApiMutationErrors
    fun updateExpo(
        @PathVariable("expo_id") expoId: String,
        @Valid @RequestBody request: UpdateExpoRequest,
    ): ResponseEntity<Void> {
        updateExpoService.execute(expoId, request)
        return ResponseEntity.noContent().build()
    }

    @DeleteMapping("/{expo_id}")
    @Operation(summary = "박람회 삭제")
    @ApiResponse(responseCode = "204", description = "처리 완료 (본문 없음)")
    @ApiNotFoundError
    @ApiConflictError
    @ApiIntegrationErrors
    fun deleteExpo(
        @PathVariable("expo_id") expoId: String,
    ): ResponseEntity<Void> {
        deleteExpoService.execute(expoId)
        return ResponseEntity.noContent().build()
    }
}
