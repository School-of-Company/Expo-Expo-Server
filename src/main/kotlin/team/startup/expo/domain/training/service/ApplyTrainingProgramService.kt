package team.startup.expo.domain.training.service

import team.startup.expo.domain.training.presentation.dto.request.ApplyTrainingProgramRequest
import team.startup.expo.domain.training.presentation.dto.request.ApplyTrainingProgramsRequest

interface ApplyTrainingProgramService {
    fun execute(
        programId: Long,
        request: ApplyTrainingProgramRequest,
    )

    fun executeList(request: ApplyTrainingProgramsRequest)
}
