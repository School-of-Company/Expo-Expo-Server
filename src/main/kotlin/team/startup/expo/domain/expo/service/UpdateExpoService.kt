package team.startup.expo.domain.expo.service

import team.startup.expo.domain.expo.presentation.dto.request.UpdateExpoRequest

interface UpdateExpoService {
    fun execute(
        expoId: String,
        request: UpdateExpoRequest,
    )
}
