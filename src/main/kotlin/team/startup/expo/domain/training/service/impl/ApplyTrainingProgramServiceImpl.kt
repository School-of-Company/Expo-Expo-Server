package team.startup.expo.domain.training.service.impl

import org.springframework.data.repository.findByIdOrNull
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import team.startup.expo.domain.expo.repository.ExpoRepository
import team.startup.expo.domain.training.presentation.dto.request.ApplyTrainingProgramRequest
import team.startup.expo.domain.training.presentation.dto.request.ApplyTrainingProgramsRequest
import team.startup.expo.domain.training.repository.TrainingProgramRepository
import team.startup.expo.domain.training.service.ApplyTrainingProgramService
import team.startup.expo.domain.training.service.TrainingDependenciesClient
import team.startup.expo.global.exception.ExpectedException

@Service
class ApplyTrainingProgramServiceImpl(
    private val expos: ExpoRepository,
    private val programs: TrainingProgramRepository,
    private val dependencies: TrainingDependenciesClient,
) : ApplyTrainingProgramService {
    @Transactional
    override fun execute(
        programId: Long,
        request: ApplyTrainingProgramRequest,
    ) {
        val program =
            programs.findByIdOrNull(programId)
                ?: throw ExpectedException(HttpStatus.NOT_FOUND, "연수 프로그램을 찾지 못했습니다.")
        val expoId = program.expo?.id ?: throw ExpectedException(HttpStatus.BAD_GATEWAY, "프로그램의 박람회 정보가 없습니다.")
        ensureExpoOpen(expoId)
        val traineeId = dependencies.resolveTrainee(expoId, request.trainingId)
        dependencies.apply(expoId, traineeId, listOf(program))
    }

    @Transactional
    override fun executeList(request: ApplyTrainingProgramsRequest) {
        val ids = request.trainingProIds
        if (ids.isEmpty() || ids.any { it <= 0 } || ids.distinct().size != ids.size) {
            throw ExpectedException(HttpStatus.BAD_REQUEST, "연수 프로그램 ID가 올바르지 않습니다.")
        }
        val found = programs.findAllByIdIn(ids)
        if (found.size != ids.size) throw ExpectedException(HttpStatus.NOT_FOUND, "연수 프로그램을 찾지 못했습니다.")
        if (found.any { it.expo == null }) throw ExpectedException(HttpStatus.BAD_GATEWAY, "프로그램의 박람회 정보가 없습니다.")
        val expoId = requireNotNull(found.first().expo).id
        if (found.any { it.expo?.id != expoId }) throw ExpectedException(HttpStatus.NOT_FOUND, "연수 프로그램을 찾지 못했습니다.")
        ensureExpoOpen(expoId)
        val traineeId = dependencies.resolveTrainee(expoId, request.trainingId)
        dependencies.apply(expoId, traineeId, found)
    }

    private fun ensureExpoOpen(expoId: String) {
        val expo = expos.findLockedById(expoId) ?: throw ExpectedException(HttpStatus.NOT_FOUND, "박람회를 찾지 못 했습니다.")
        if (expo.deletingAt != null) throw ExpectedException(HttpStatus.CONFLICT, "삭제 중인 박람회입니다.")
    }
}
