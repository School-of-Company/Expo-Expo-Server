package team.startup.expo.domain.standard.presentation

import io.swagger.v3.oas.annotations.Operation
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
import team.startup.expo.domain.standard.presentation.dto.request.AddStandardProgramRequest
import team.startup.expo.domain.standard.presentation.dto.request.ApplyStandardProgramsRequest
import team.startup.expo.domain.standard.presentation.dto.request.UpdateStandardProgramRequest
import team.startup.expo.domain.standard.presentation.dto.response.StandardProgramParticipantResponse
import team.startup.expo.domain.standard.presentation.dto.response.StandardProgramResponse
import team.startup.expo.domain.standard.service.ApplyStandardProgramsService
import team.startup.expo.domain.standard.service.CreateStandardProgramListService
import team.startup.expo.domain.standard.service.CreateStandardProgramService
import team.startup.expo.domain.standard.service.DeleteStandardProgramService
import team.startup.expo.domain.standard.service.GetStandardProgramListService
import team.startup.expo.domain.standard.service.GetStandardProgramParticipantsService
import team.startup.expo.domain.standard.service.UpdateStandardProgramService

@RestController
@RequestMapping("/standard")
class StandardController(
    private val getStandardProgramListService: GetStandardProgramListService,
    private val createStandardProgramService: CreateStandardProgramService,
    private val createStandardProgramListService: CreateStandardProgramListService,
    private val updateStandardProgramService: UpdateStandardProgramService,
    private val applyStandardProgramsService: ApplyStandardProgramsService,
    private val getStandardProgramParticipantsService: GetStandardProgramParticipantsService,
    private val deleteStandardProgramService: DeleteStandardProgramService,
) {
    @PostMapping("/{expo_id}")
    fun create(
        @PathVariable("expo_id") expoId: String,
        @Valid @RequestBody request: AddStandardProgramRequest,
    ): ResponseEntity<Void> {
        createStandardProgramService.execute(expoId, request)
        return ResponseEntity.status(HttpStatus.CREATED).build()
    }

    @PostMapping("/list/{expo_id}")
    fun createAll(
        @PathVariable("expo_id") expoId: String,
        @RequestBody requests: List<@Valid AddStandardProgramRequest>,
    ): ResponseEntity<Void> {
        createStandardProgramListService.execute(expoId, requests)
        return ResponseEntity.status(HttpStatus.CREATED).build()
    }

    @PatchMapping("/{standardPro_id}")
    fun update(
        @PathVariable("standardPro_id") programId: Long,
        @Valid @RequestBody request: UpdateStandardProgramRequest,
    ): ResponseEntity<Void> {
        updateStandardProgramService.execute(programId, request)
        return ResponseEntity.noContent().build()
    }

    @Operation(summary = "일반 프로그램 목록 조회")
    @GetMapping("/program/{expo_id}")
    fun getProgramList(
        @PathVariable("expo_id") expoId: String,
    ): ResponseEntity<List<StandardProgramResponse>> = ResponseEntity.ok(getStandardProgramListService.execute(expoId))

    @DeleteMapping("/{standardPro_id}")
    fun delete(
        @PathVariable("standardPro_id") programId: Long,
    ): ResponseEntity<Void> {
        deleteStandardProgramService.execute(programId)
        return ResponseEntity.noContent().build()
    }

    @GetMapping("/{standardPro_id}")
    fun participants(
        @PathVariable("standardPro_id") programId: Long,
    ): ResponseEntity<List<StandardProgramParticipantResponse>> =
        ResponseEntity.ok(getStandardProgramParticipantsService.execute(programId))

    @PostMapping("/application/{expo_id}")
    fun apply(
        @PathVariable("expo_id") expoId: String,
        @Valid @RequestBody request: ApplyStandardProgramsRequest,
    ): ResponseEntity<Void> {
        applyStandardProgramsService.execute(expoId, request)
        return ResponseEntity.status(HttpStatus.CREATED).build()
    }
}
