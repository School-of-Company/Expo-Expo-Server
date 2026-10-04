package team.startup.expo.domain.expo.presentation

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.ExampleObject
import io.swagger.v3.oas.annotations.responses.ApiResponse
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
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
import team.startup.expo.domain.expo.presentation.dto.response.CreateExpoResponse
import team.startup.expo.domain.expo.presentation.dto.response.ExpoDetailResponse
import team.startup.expo.domain.expo.presentation.dto.response.ExpoValidationResponse
import team.startup.expo.domain.expo.service.CreateExpoService
import team.startup.expo.domain.expo.service.GetExpoDetailService
import team.startup.expo.domain.expo.service.GetExpoListService
import team.startup.expo.domain.expo.service.GetExpoValidationService
import team.startup.expo.domain.expo.service.UpdateExpoService

@RestController
@RequestMapping("/expo")
class ExpoController(
    private val createExpoService: CreateExpoService,
    private val getExpoListService: GetExpoListService,
    private val getExpoDetailService: GetExpoDetailService,
    private val updateExpoService: UpdateExpoService,
    private val getExpoValidationService: GetExpoValidationService,
) {
    @PostMapping
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
    fun getExpoList(
        @Valid @ModelAttribute request: ExpoPageRequest,
    ): ResponseEntity<Any> =
        ResponseEntity.ok(if (request.isPaged) getExpoListService.executePage(request) else getExpoListService.execute())

    @GetMapping("/{expo_id}")
    fun getExpoDetail(
        @PathVariable("expo_id") expoId: String,
    ): ResponseEntity<ExpoDetailResponse> = ResponseEntity.ok(getExpoDetailService.execute(expoId))

    @GetMapping("/valid")
    fun getExpoValidation(): ResponseEntity<ExpoValidationResponse> = ResponseEntity.ok(getExpoValidationService.execute())

    @Operation(
        summary = "박람회 수정",
        description = "모든 필드를 필수로 받아 전체 교체합니다. 목록에서 빠진 기존 프로그램은 삭제합니다.",
    )
    @PatchMapping("/{expo_id}")
    fun updateExpo(
        @PathVariable("expo_id") expoId: String,
        @Valid @RequestBody request: UpdateExpoRequest,
    ): ResponseEntity<Void> {
        updateExpoService.execute(expoId, request)
        return ResponseEntity.noContent().build()
    }
}
