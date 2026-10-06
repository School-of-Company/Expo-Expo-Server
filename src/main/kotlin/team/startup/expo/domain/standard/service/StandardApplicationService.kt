package team.startup.expo.domain.standard.service

import org.springframework.data.repository.findByIdOrNull
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import team.startup.expo.domain.expo.repository.ExpoRepository
import team.startup.expo.domain.standard.presentation.dto.request.ApplyStandardProgramsRequest
import team.startup.expo.domain.standard.presentation.dto.response.StandardProgramParticipantResponse
import team.startup.expo.domain.standard.repository.StandardProgramRepository
import team.startup.expo.global.exception.ExpectedException

@Service
class StandardApplicationService(
    private val expos: ExpoRepository,
    private val programs: StandardProgramRepository,
    private val dependencies: StandardDependenciesClient,
) {
    @Transactional
    fun apply(
        expoId: String,
        request: ApplyStandardProgramsRequest,
    ) {
        expos.findByIdOrNull(expoId)
            ?: throw ExpectedException(HttpStatus.NOT_FOUND, "박람회를 찾지 못 했습니다.")
        val participantId = dependencies.resolveParticipant(expoId, request.phoneNumber)
        val programIds = request.standardProIds.distinct()
        val found = programs.findAllById(programIds)
        if (found.size != programIds.size || found.any { it.expo?.id != expoId }) {
            throw ExpectedException(HttpStatus.NOT_FOUND, "일반 프로그램을 찾지 못 했습니다.")
        }
        if (programIds.isNotEmpty()) dependencies.apply(expoId, participantId, programIds)
    }

    @Transactional(readOnly = true)
    fun participants(programId: Long): List<StandardProgramParticipantResponse> {
        val program =
            programs.findByIdOrNull(programId)
                ?: throw ExpectedException(HttpStatus.NOT_FOUND, "일반 프로그램을 찾지 못 했습니다.")
        val applications = dependencies.applications(programId)
        if (applications.isEmpty()) return emptyList()
        val expoId = program.expo?.id ?: throw ExpectedException(HttpStatus.BAD_GATEWAY, "프로그램의 박람회 정보가 없습니다.")
        val participantIds = applications.map { it.participantId }.distinct()
        val names = dependencies.participantNames(expoId, participantIds)
        val namesById = names.associateBy { it.participantId }
        if (names.size != participantIds.size || namesById.keys != participantIds.toSet()) {
            throw ExpectedException(HttpStatus.BAD_GATEWAY, "참가자 조회 결과가 신청 기록과 일치하지 않습니다.")
        }
        return applications.map { application ->
            StandardProgramParticipantResponse(
                id = application.applicationId,
                name = requireNotNull(namesById[application.participantId]).name,
                programName = program.title,
                status = application.status,
                entryTime = application.entryTime,
                leaveTime = application.leaveTime,
            )
        }
    }

    @Transactional
    fun delete(programId: Long) {
        val program =
            programs.findByIdOrNull(programId)
                ?: throw ExpectedException(HttpStatus.NOT_FOUND, "일반 프로그램을 찾지 못 했습니다.")
        dependencies.deleteApplications(programId)
        programs.delete(program)
    }
}
