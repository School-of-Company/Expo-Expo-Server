package team.startup.expo.domain.training.service

import team.startup.expo.domain.training.presentation.dto.request.TrainingProgramBatchRequest
import team.startup.expo.domain.training.presentation.dto.response.TrainingProgramResponse

interface GetTrainingProgramBatchService {
    fun execute(
        expoId: String,
        request: TrainingProgramBatchRequest,
    ): List<TrainingProgramResponse>
}
