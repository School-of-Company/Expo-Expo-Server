package team.startup.expo.domain.training.service

import team.startup.expo.domain.training.presentation.dto.request.AddTrainingProgramRequest

interface CreateTrainingProgramListService {
    fun execute(
        expoId: String,
        requests: List<AddTrainingProgramRequest>,
    )
}
