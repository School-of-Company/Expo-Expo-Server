package team.startup.expo.domain.expo.presentation

import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import team.startup.expo.domain.expo.presentation.dto.request.CreateExpoRequest
import team.startup.expo.domain.expo.presentation.dto.request.UpdateExpoRequest
import team.startup.expo.domain.expo.presentation.dto.response.CreateExpoResponse
import team.startup.expo.domain.expo.presentation.dto.response.ExpoDetailResponse
import team.startup.expo.domain.expo.presentation.dto.response.ExpoSummaryResponse
import team.startup.expo.domain.expo.service.CreateExpoService
import team.startup.expo.domain.expo.service.GetExpoDetailService
import team.startup.expo.domain.expo.service.GetExpoListService
import team.startup.expo.domain.expo.service.UpdateExpoService

@RestController
@RequestMapping("/expo")
class ExpoController(
    private val createExpoService: CreateExpoService,
    private val getExpoListService: GetExpoListService,
    private val getExpoDetailService: GetExpoDetailService,
    private val updateExpoService: UpdateExpoService,
) {
    @PostMapping
    fun createExpo(
        @Valid @RequestBody request: CreateExpoRequest,
    ): ResponseEntity<CreateExpoResponse> =
        ResponseEntity
            .status(HttpStatus.CREATED)
            .body(createExpoService.execute(request))

    @GetMapping
    fun getExpoList(): ResponseEntity<List<ExpoSummaryResponse>> = ResponseEntity.ok(getExpoListService.execute())

    @GetMapping("/{expo_id}")
    fun getExpoDetail(
        @PathVariable("expo_id") expoId: String,
    ): ResponseEntity<ExpoDetailResponse> = ResponseEntity.ok(getExpoDetailService.execute(expoId))

    @PatchMapping("/{expo_id}")
    fun updateExpo(
        @PathVariable("expo_id") expoId: String,
        @Valid @RequestBody request: UpdateExpoRequest,
    ): ResponseEntity<Void> {
        updateExpoService.execute(expoId, request)
        return ResponseEntity.noContent().build()
    }
}
