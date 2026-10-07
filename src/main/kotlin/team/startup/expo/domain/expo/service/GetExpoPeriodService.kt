package team.startup.expo.domain.expo.service

import team.startup.expo.domain.expo.presentation.dto.response.ExpoPeriodResponse

interface GetExpoPeriodService {
    fun execute(expoId: String): ExpoPeriodResponse
}
