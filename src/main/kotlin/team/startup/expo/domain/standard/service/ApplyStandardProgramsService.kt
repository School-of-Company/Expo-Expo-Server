package team.startup.expo.domain.standard.service

import team.startup.expo.domain.standard.presentation.dto.request.ApplyStandardProgramsRequest

interface ApplyStandardProgramsService {
    fun execute(
        expoId: String,
        request: ApplyStandardProgramsRequest,
    )
}
