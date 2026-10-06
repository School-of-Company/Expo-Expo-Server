package team.startup.expo.domain.training.service

import team.startup.expo.domain.training.presentation.dto.response.TrainingProgramTraineeResponse

interface GetTrainingProgramTraineesService {
    fun execute(programId: Long): List<TrainingProgramTraineeResponse>
}
