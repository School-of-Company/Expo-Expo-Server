package team.startup.expo.domain.standard.presentation

import io.swagger.v3.oas.annotations.Operation
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import team.startup.expo.domain.standard.presentation.dto.response.StandardProgramResponse
import team.startup.expo.domain.standard.service.GetStandardProgramListService

@RestController
@RequestMapping("/standard")
class StandardController(
    private val getStandardProgramListService: GetStandardProgramListService,
) {
    @Operation(summary = "일반 프로그램 목록 조회")
    @GetMapping("/program/{expo_id}")
    fun getProgramList(
        @PathVariable("expo_id") expoId: String,
    ): ResponseEntity<List<StandardProgramResponse>> = ResponseEntity.ok(getStandardProgramListService.execute(expoId))
}
