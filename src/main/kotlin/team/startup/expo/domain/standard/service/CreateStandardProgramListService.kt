package team.startup.expo.domain.standard.service

import team.startup.expo.domain.standard.presentation.dto.request.AddStandardProgramRequest

interface CreateStandardProgramListService {
    fun execute(
        expoId: String,
        requests: List<AddStandardProgramRequest>,
    )
}
