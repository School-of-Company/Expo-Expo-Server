package team.startup.expo.domain.training.service

import team.startup.expo.domain.training.presentation.dto.request.ApplyTrainingProgramsRequest

interface ApplyTrainingProgramListService {
    fun execute(request: ApplyTrainingProgramsRequest)
}
