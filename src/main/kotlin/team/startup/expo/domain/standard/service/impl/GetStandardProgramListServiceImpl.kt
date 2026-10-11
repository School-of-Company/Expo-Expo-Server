package team.startup.expo.domain.standard.service.impl

import org.springframework.data.repository.findByIdOrNull
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import team.startup.expo.domain.expo.presentation.dto.response.ProgramType
import team.startup.expo.domain.expo.repository.ExpoRepository
import team.startup.expo.domain.expo.service.ProgramPolicySupport
import team.startup.expo.domain.standard.presentation.dto.response.StandardProgramResponse
import team.startup.expo.domain.standard.repository.StandardProgramRepository
import team.startup.expo.domain.standard.service.GetStandardProgramListService
import team.startup.expo.global.exception.ExpectedException

@Service
class GetStandardProgramListServiceImpl(
    private val expoRepository: ExpoRepository,
    private val policies: ProgramPolicySupport,
    private val standardProgramRepository: StandardProgramRepository,
) : GetStandardProgramListService {
    @Transactional(readOnly = true)
    override fun execute(expoId: String): List<StandardProgramResponse> {
        expoRepository.findByIdOrNull(expoId)
            ?: throw ExpectedException(HttpStatus.NOT_FOUND, "박람회를 찾지 못 했습니다.")

        val pending = policies.pendingRevisions(expoId, ProgramType.STANDARD)
        return standardProgramRepository.findByExpoIdOrderByIdAsc(expoId).map { program ->
            StandardProgramResponse(
                id = requireNotNull(program.id),
                title = program.title,
                expoId = expoId,
                groupId = program.groupId,
                policy = policies.policy(program.policyJson),
                revision = program.revision,
                pendingRevision = pending[program.id],
                startedAt = program.startedAt,
                endedAt = program.endedAt,
            )
        }
    }
}
