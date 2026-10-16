package team.startup.expo.domain.training.service.impl

import org.springframework.data.repository.findByIdOrNull
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import team.startup.expo.domain.expo.presentation.dto.response.ProgramType
import team.startup.expo.domain.expo.repository.ExpoRepository
import team.startup.expo.domain.expo.service.ProgramPolicySupport
import team.startup.expo.domain.training.presentation.dto.response.TrainingProgramResponse
import team.startup.expo.domain.training.repository.TrainingProgramRepository
import team.startup.expo.domain.training.service.GetTrainingProgramListService
import team.startup.expo.global.exception.ExpectedException

@Service
class GetTrainingProgramListServiceImpl(
    private val expoRepository: ExpoRepository,
    private val policies: ProgramPolicySupport,
    private val trainingProgramRepository: TrainingProgramRepository,
) : GetTrainingProgramListService {
    @Transactional(readOnly = true)
    override fun execute(expoId: String): List<TrainingProgramResponse> {
        expoRepository.findByIdOrNull(expoId)
            ?: throw ExpectedException(HttpStatus.NOT_FOUND, "박람회를 찾지 못 했습니다.")

        val pending = policies.pendingRevisions(expoId, ProgramType.TRAINING)
        return trainingProgramRepository.findByExpoIdOrderByIdAsc(expoId).map { program ->
            TrainingProgramResponse(
                id = requireNotNull(program.id),
                title = program.title,
                place = program.place,
                expoId = expoId,
                groupId = program.groupId,
                policy = policies.policy(program.policyJson),
                revision = program.revision,
                pendingRevision = pending[program.id],
                startedAt = program.startedAt,
                endedAt = program.endedAt,
                category = program.category,
            )
        }
    }
}
