package team.startup.expo.domain.standard.service

import team.startup.expo.domain.standard.presentation.dto.request.AddStandardProgramRequest

interface CreateStandardProgramService {
    fun execute(
        expoId: String,
        request: AddStandardProgramRequest,
    )
}
