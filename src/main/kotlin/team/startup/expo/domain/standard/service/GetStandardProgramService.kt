package team.startup.expo.domain.standard.service

import team.startup.expo.domain.standard.presentation.dto.response.StandardProgramTitleResponse

interface GetStandardProgramService {
    fun execute(
        expoId: String,
        programId: Long,
    ): StandardProgramTitleResponse
}
