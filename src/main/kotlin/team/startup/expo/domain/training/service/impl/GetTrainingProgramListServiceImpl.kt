package team.startup.expo.domain.training.service.impl

import org.springframework.data.repository.findByIdOrNull
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import team.startup.expo.domain.expo.repository.ExpoRepository
import team.startup.expo.domain.training.presentation.dto.response.TrainingProgramResponse
import team.startup.expo.domain.training.repository.TrainingProgramRepository
import team.startup.expo.domain.training.service.GetTrainingProgramListService
import team.startup.expo.global.exception.ExpectedException

@Service
class GetTrainingProgramListServiceImpl(
    private val expoRepository: ExpoRepository,
    private val trainingProgramRepository: TrainingProgramRepository,
) : GetTrainingProgramListService {
    @Transactional(readOnly = true)
    override fun execute(expoId: String): List<TrainingProgramResponse> {
        expoRepository.findByIdOrNull(expoId)
            ?: throw ExpectedException(HttpStatus.NOT_FOUND, "박람회를 찾을 수 없습니다.")

        return trainingProgramRepository.findByExpoIdOrderByIdAsc(expoId).map { program ->
            TrainingProgramResponse(
                id = requireNotNull(program.id),
                title = program.title,
                startedAt = program.startedAt,
                endedAt = program.endedAt,
                category = program.category,
            )
        }
    }
}
