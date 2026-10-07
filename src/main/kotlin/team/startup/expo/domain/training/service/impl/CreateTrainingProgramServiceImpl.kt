package team.startup.expo.domain.training.service.impl

import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import team.startup.expo.domain.expo.repository.ExpoRepository
import team.startup.expo.domain.training.entity.TrainingProgram
import team.startup.expo.domain.training.presentation.dto.request.AddTrainingProgramRequest
import team.startup.expo.domain.training.repository.TrainingProgramRepository
import team.startup.expo.domain.training.service.CreateTrainingProgramService
import team.startup.expo.global.exception.ExpectedException

@Service
class CreateTrainingProgramServiceImpl(
    private val expos: ExpoRepository,
    private val programs: TrainingProgramRepository,
) : CreateTrainingProgramService {
    @Transactional
    override fun execute(
        expoId: String,
        request: AddTrainingProgramRequest,
    ) {
        val expo = expos.findLockedById(expoId) ?: throw ExpectedException(HttpStatus.NOT_FOUND, "박람회를 찾을 수 없습니다.")
        if (expo.deletingAt != null) throw ExpectedException(HttpStatus.CONFLICT, "삭제 중인 박람회입니다.")
        programs.save(
            TrainingProgram(
                title = request.title,
                startedAt = request.startedAt.toString(),
                endedAt = request.endedAt.toString(),
                category = request.category,
                expo = expo,
            ),
        )
    }
}
