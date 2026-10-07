package team.startup.expo.domain.standard.service.impl

import org.springframework.data.repository.findByIdOrNull
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import team.startup.expo.domain.standard.presentation.dto.response.StandardProgramParticipantResponse
import team.startup.expo.domain.standard.repository.StandardProgramRepository
import team.startup.expo.domain.standard.service.GetStandardProgramParticipantsService
import team.startup.expo.domain.standard.service.StandardDependenciesClient
import team.startup.expo.global.attendance.ProgramAttendanceClient
import team.startup.expo.global.exception.ExpectedException

@Service
class GetStandardProgramParticipantsServiceImpl(
    private val programs: StandardProgramRepository,
    private val dependencies: StandardDependenciesClient,
    private val attendances: ProgramAttendanceClient,
) : GetStandardProgramParticipantsService {
    @Transactional(readOnly = true)
    override fun execute(programId: Long): List<StandardProgramParticipantResponse> {
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
        val attendanceById = attendances.standard(programId)
        return applications.map { application ->
            val attendance = attendanceById[application.participantId]
            StandardProgramParticipantResponse(
                id = application.applicationId,
                name = requireNotNull(namesById[application.participantId]).name,
                programName = program.title,
                status = attendance != null,
                entryTime = attendance?.entryTime,
                leaveTime = attendance?.leaveTime,
            )
        }
    }
}
