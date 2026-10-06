package team.startup.expo.domain.standard.service

import team.startup.expo.domain.standard.presentation.dto.response.StandardProgramParticipantResponse

interface GetStandardProgramParticipantsService {
    fun execute(programId: Long): List<StandardProgramParticipantResponse>
}
