package team.startup.expo.domain.standard.service.impl

import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import team.startup.expo.domain.expo.repository.ExpoRepository
import team.startup.expo.domain.standard.presentation.dto.request.ApplyStandardProgramsRequest
import team.startup.expo.domain.standard.repository.StandardProgramRepository
import team.startup.expo.domain.standard.service.ApplyStandardProgramsService
import team.startup.expo.domain.standard.service.StandardDependenciesClient
import team.startup.expo.global.exception.ExpectedException

@Service
class ApplyStandardProgramsServiceImpl(
    private val expos: ExpoRepository,
    private val programs: StandardProgramRepository,
    private val dependencies: StandardDependenciesClient,
) : ApplyStandardProgramsService {
    @Transactional
    override fun execute(
        expoId: String,
        request: ApplyStandardProgramsRequest,
    ) {
        val expo =
            expos.findLockedById(expoId)
                ?: throw ExpectedException(HttpStatus.NOT_FOUND, "박람회를 찾지 못 했습니다.")
        if (expo.deletingAt != null) throw ExpectedException(HttpStatus.CONFLICT, "삭제 중인 박람회입니다.")
        val participantId = dependencies.resolveParticipant(expoId, request.phoneNumber)
        val programIds = request.standardProIds.distinct()
        val found = programs.findAllById(programIds)
        if (found.size != programIds.size || found.any { it.expo?.id != expoId }) {
            throw ExpectedException(HttpStatus.NOT_FOUND, "일반 프로그램을 찾지 못 했습니다.")
        }
        if (programIds.isNotEmpty()) dependencies.apply(expoId, participantId, programIds)
    }
}
