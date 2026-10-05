package team.startup.expo.domain.training.service

import team.startup.expo.domain.training.presentation.dto.request.AddTrainingProgramRequest
import team.startup.expo.domain.training.presentation.dto.request.UpdateTrainingProgramRequest

interface TrainingProgramWriteService {
    fun add(
        expoId: String,
        request: AddTrainingProgramRequest,
    )

    fun addAll(
        expoId: String,
        requests: List<AddTrainingProgramRequest>,
    )

    fun update(
        trainingProgramId: Long,
        request: UpdateTrainingProgramRequest,
    )
}
