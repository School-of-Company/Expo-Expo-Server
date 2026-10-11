package team.startup.expo.domain.expo.service

import team.startup.expo.domain.expo.presentation.dto.request.ProgramFormChangeRequest
import team.startup.expo.domain.expo.presentation.dto.response.ProgramFormChangeResponse
import java.util.UUID

interface ReserveProgramFormChangeService {
    fun execute(
        formId: UUID,
        changeId: UUID,
        request: ProgramFormChangeRequest,
    ): ProgramFormChangeResponse
}
