package team.startup.expo.domain.training.service.impl

import org.springframework.data.repository.findByIdOrNull
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import team.startup.expo.domain.expo.repository.ExpoRepository
import team.startup.expo.domain.training.repository.TrainingProgramRepository
import team.startup.expo.domain.training.service.DeleteTrainingProgramService
import team.startup.expo.domain.training.service.TrainingDependenciesClient
import team.startup.expo.global.exception.ExpectedException

@Service
class DeleteTrainingProgramServiceImpl(
    private val programs: TrainingProgramRepository,
    private val dependencies: TrainingDependenciesClient,
    private val expos: ExpoRepository,
) : DeleteTrainingProgramService {
    @Transactional
    override fun execute(programId: Long) {
        val program =
            programs.findByIdOrNull(programId)
                ?: throw ExpectedException(HttpStatus.NOT_FOUND, "연수 프로그램을 찾지 못했습니다.")
        val expoId = program.expo?.id ?: throw ExpectedException(HttpStatus.NOT_FOUND, "박람회를 찾지 못 했습니다.")
        expos.findLockedById(expoId)
            ?: throw ExpectedException(HttpStatus.NOT_FOUND, "박람회를 찾지 못 했습니다.")
        if (expos.existsByIdAndDeletingAtIsNotNull(expoId)) throw ExpectedException(HttpStatus.CONFLICT, "삭제 중인 박람회입니다.")
        dependencies.deleteApplications(programId)
        programs.delete(program)
    }
}
