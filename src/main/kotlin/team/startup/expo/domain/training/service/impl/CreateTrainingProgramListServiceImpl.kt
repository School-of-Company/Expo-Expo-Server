package team.startup.expo.domain.training.service.impl

import org.springframework.stereotype.Service
import team.startup.expo.domain.training.presentation.dto.request.AddTrainingProgramRequest
import team.startup.expo.domain.training.service.CreateTrainingProgramListService
import team.startup.expo.domain.training.service.TrainingProgramCreationSupport

@Service
class CreateTrainingProgramListServiceImpl(
    private val creation: TrainingProgramCreationSupport,
) : CreateTrainingProgramListService {
    override fun execute(
        expoId: String,
        requests: List<AddTrainingProgramRequest>,
    ) {
        creation.create(expoId, requests)
    }
}
