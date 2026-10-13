package team.startup.expo.domain.expo.service

import team.startup.expo.domain.expo.presentation.dto.request.AdoptPreregisterWaitingCapacityRequest

interface AdoptPreregisterWaitingCapacityService {
    fun execute(
        expoId: String,
        sessionId: Long,
        request: AdoptPreregisterWaitingCapacityRequest,
    )
}
