package team.startup.expo.domain.standard.service.impl

import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import team.startup.expo.domain.expo.repository.ExpoRepository
import team.startup.expo.domain.expo.service.ProgramPolicySupport
import team.startup.expo.domain.standard.presentation.dto.request.ApplyStandardProgramsRequest
import team.startup.expo.domain.standard.repository.StandardProgramRepository
import team.startup.expo.domain.standard.service.ApplyStandardProgramsService
import team.startup.expo.domain.standard.service.StandardDependenciesClient
import team.startup.expo.global.exception.ExpectedException

@Service
class ApplyStandardProgramsServiceImpl(
    private val expos: ExpoRepository,
    private val policies: ProgramPolicySupport,
    private val programs: StandardProgramRepository,
    private val dependencies: StandardDependenciesClient,
    private val transactions: TransactionTemplate,
) : ApplyStandardProgramsService {
    // #160: 연수 신청처럼 박람회 행 락은 로컬 확인에만 쓰고, User·Application 호출은 트랜잭션 밖에서 한다.
    override fun execute(
        expoId: String,
        request: ApplyStandardProgramsRequest,
    ) {
        val participantId = dependencies.resolveParticipant(expoId, request.phoneNumber)
        val programIds = request.standardProIds.distinct()
        transactions.executeWithoutResult {
            val expo =
                expos.findLockedById(expoId)
                    ?: throw ExpectedException(HttpStatus.NOT_FOUND, "박람회를 찾지 못 했습니다.")
            if (expo.deletingAt != null) throw ExpectedException(HttpStatus.CONFLICT, "삭제 중인 박람회입니다.")
            val found = programs.findAllById(programIds)
            if (found.size != programIds.size || found.any { it.expo?.id != expoId }) {
                throw ExpectedException(HttpStatus.NOT_FOUND, "일반 프로그램을 찾지 못 했습니다.")
            }
            found.forEach { policies.ensureAdmission(expoId, it.policyJson) }
        }
        if (programIds.isNotEmpty()) dependencies.apply(expoId, participantId, programIds)
    }
}
