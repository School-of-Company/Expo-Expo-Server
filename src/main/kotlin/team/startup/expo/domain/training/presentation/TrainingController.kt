package team.startup.expo.domain.training.presentation

import io.swagger.v3.oas.annotations.Operation
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
import team.startup.expo.domain.training.presentation.dto.request.AddTrainingProgramRequest
import team.startup.expo.domain.training.presentation.dto.request.UpdateTrainingProgramRequest
import team.startup.expo.domain.training.presentation.dto.response.TrainingProgramResponse
import team.startup.expo.domain.training.service.GetTrainingProgramListService
import team.startup.expo.domain.training.service.TrainingProgramWriteService

@RestController
@RequestMapping("/training")
class TrainingController(
    private val getTrainingProgramListService: GetTrainingProgramListService,
    private val trainingProgramWriteService: TrainingProgramWriteService,
) {
    @Operation(summary = "연수 프로그램 목록 조회")
    @GetMapping("/program/{expo_id}")
    fun getProgramList(
        @PathVariable("expo_id") expoId: String,
    ): ResponseEntity<List<TrainingProgramResponse>> = ResponseEntity.ok(getTrainingProgramListService.execute(expoId))

    @PostMapping("/{expo_id}")
    fun addProgram(
        @PathVariable("expo_id") expoId: String,
        @Valid @RequestBody request: AddTrainingProgramRequest,
    ): ResponseEntity<Void> {
        trainingProgramWriteService.add(expoId, request)
        return ResponseEntity.status(HttpStatus.CREATED).build()
    }

    @PostMapping("/list/{expo_id}")
    fun addPrograms(
        @PathVariable("expo_id") expoId: String,
        @Valid @RequestBody requests: List<AddTrainingProgramRequest>,
    ): ResponseEntity<Void> {
        trainingProgramWriteService.addAll(expoId, requests)
        return ResponseEntity.status(HttpStatus.CREATED).build()
    }

    @PatchMapping("/{trainingPro_id}")
    fun updateProgram(
        @PathVariable("trainingPro_id") trainingProgramId: Long,
        @Valid @RequestBody request: UpdateTrainingProgramRequest,
    ): ResponseEntity<Void> {
        trainingProgramWriteService.update(trainingProgramId, request)
        return ResponseEntity.noContent().build()
    }
}
