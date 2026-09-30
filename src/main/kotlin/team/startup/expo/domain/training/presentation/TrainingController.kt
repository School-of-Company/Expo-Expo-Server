package team.startup.expo.domain.training.presentation

import io.swagger.v3.oas.annotations.Operation
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import team.startup.expo.domain.training.presentation.dto.response.TrainingProgramResponse
import team.startup.expo.domain.training.service.GetTrainingProgramListService

@RestController
@RequestMapping("/training")
class TrainingController(
    private val getTrainingProgramListService: GetTrainingProgramListService,
) {
    @Operation(summary = "연수 프로그램 목록 조회")
    @GetMapping("/program/{expo_id}")
    fun getProgramList(
        @PathVariable("expo_id") expoId: String,
    ): ResponseEntity<List<TrainingProgramResponse>> = ResponseEntity.ok(getTrainingProgramListService.execute(expoId))
}
