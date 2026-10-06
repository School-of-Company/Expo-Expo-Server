package team.startup.expo.domain.training.service

import team.startup.expo.domain.training.presentation.dto.response.TrainingProgramResponse

interface GetTrainingProgramListService {
    fun execute(expoId: String): List<TrainingProgramResponse>
}
