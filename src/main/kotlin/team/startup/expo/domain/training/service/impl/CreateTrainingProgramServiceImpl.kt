package team.startup.expo.domain.training.service.impl

import org.springframework.stereotype.Service
import team.startup.expo.domain.training.presentation.dto.request.AddTrainingProgramRequest
import team.startup.expo.domain.training.service.CreateTrainingProgramService
import team.startup.expo.domain.training.service.TrainingProgramCreationSupport

@Service
class CreateTrainingProgramServiceImpl(
    private val creation: TrainingProgramCreationSupport,
) : CreateTrainingProgramService {
    override fun execute(
        expoId: String,
        request: AddTrainingProgramRequest,
    ) {
        creation.create(expoId, listOf(request))
    }
}
