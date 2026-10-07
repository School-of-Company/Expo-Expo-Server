package team.startup.expo.domain.training.service

import team.startup.expo.domain.training.presentation.dto.request.ApplyTrainingProgramRequest

interface ApplyTrainingProgramService {
    fun execute(
        programId: Long,
        request: ApplyTrainingProgramRequest,
    )
}
