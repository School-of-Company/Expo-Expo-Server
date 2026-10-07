package team.startup.expo.domain.training.service

import team.startup.expo.domain.training.presentation.dto.request.AddTrainingProgramRequest

interface CreateTrainingProgramService {
    fun execute(
        expoId: String,
        request: AddTrainingProgramRequest,
    )
}
