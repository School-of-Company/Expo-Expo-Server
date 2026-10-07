package team.startup.expo.domain.training.service

import team.startup.expo.domain.training.presentation.dto.request.UpdateTrainingProgramRequest

interface UpdateTrainingProgramService {
    fun execute(
        trainingProgramId: Long,
        request: UpdateTrainingProgramRequest,
    )
}
