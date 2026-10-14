package team.startup.expo.domain.expo.service

import team.startup.expo.domain.expo.presentation.dto.response.ProgramPromotionModesResponse

interface GetProgramPromotionModesService {
    fun execute(expoId: String): ProgramPromotionModesResponse
}
