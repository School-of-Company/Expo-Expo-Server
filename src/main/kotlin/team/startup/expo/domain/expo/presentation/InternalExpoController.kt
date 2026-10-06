package team.startup.expo.domain.expo.presentation

import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import team.startup.expo.domain.expo.presentation.dto.response.ExpoPeriodResponse
import team.startup.expo.domain.expo.service.GetExpoDetailService
import team.startup.expo.domain.standard.presentation.dto.response.StandardProgramTitleResponse
import team.startup.expo.domain.standard.service.GetStandardProgramService
import team.startup.expo.domain.training.presentation.dto.request.TrainingProgramBatchRequest
import team.startup.expo.domain.training.presentation.dto.response.TrainingProgramResponse
import team.startup.expo.domain.training.service.GetTrainingProgramBatchService

@RestController
@RequestMapping("/internal/expo")
class InternalExpoController(
    private val getExpoDetailService: GetExpoDetailService,
    private val getTrainingProgramBatchService: GetTrainingProgramBatchService,
    private val getStandardProgramService: GetStandardProgramService,
) {
    @GetMapping("/{expo_id}")
    fun getExpoPeriod(
        @PathVariable("expo_id") expoId: String,
    ): ResponseEntity<ExpoPeriodResponse> {
        val detail = getExpoDetailService.execute(expoId)
        return ResponseEntity.ok(
            ExpoPeriodResponse(title = detail.title, startedDay = detail.startedDay, finishedDay = detail.finishedDay),
        )
    }

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
