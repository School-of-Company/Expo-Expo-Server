package team.startup.expo.domain.training.service.impl

import org.springframework.data.repository.findByIdOrNull
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import team.startup.expo.domain.training.entity.TrainingProgram
import team.startup.expo.domain.training.presentation.dto.request.UpdateTrainingProgramRequest
import team.startup.expo.domain.training.repository.TrainingProgramRepository
import team.startup.expo.domain.training.service.UpdateTrainingProgramService
import team.startup.expo.global.exception.ExpectedException

@Service
class UpdateTrainingProgramServiceImpl(
    private val programs: TrainingProgramRepository,
) : UpdateTrainingProgramService {
    @Transactional
    override fun execute(
        trainingProgramId: Long,
        request: UpdateTrainingProgramRequest,
    ) {
        val existing =
            programs.findByIdOrNull(trainingProgramId)
                ?: throw ExpectedException(HttpStatus.NOT_FOUND, "연수 프로그램을 찾지 못했습니다.")
        programs.save(
            TrainingProgram(
                id = existing.id,
                title = request.title,
                startedAt = request.startedAt.toString(),
                endedAt = request.endedAt.toString(),
                category = request.category,
                expo = existing.expo,
            ),
        )
    }
}
