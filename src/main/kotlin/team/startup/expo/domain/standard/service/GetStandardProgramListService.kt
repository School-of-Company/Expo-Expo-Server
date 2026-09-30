package team.startup.expo.domain.standard.service

import team.startup.expo.domain.standard.presentation.dto.response.StandardProgramResponse

interface GetStandardProgramListService {
    fun execute(expoId: String): List<StandardProgramResponse>
}
