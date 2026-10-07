package team.startup.expo.domain.training.service

import team.startup.expo.domain.training.presentation.dto.request.ApplyTrainingProgramsWithTraineeRequest

interface ApplyTrainingProgramsWithTraineeService {
    fun execute(
        expoId: String,
        request: ApplyTrainingProgramsWithTraineeRequest,
    )
}
