package team.startup.expo.domain.expo.service.impl

import org.springframework.data.repository.findByIdOrNull
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import team.startup.expo.domain.expo.presentation.dto.response.PublicExpoResponse
import team.startup.expo.domain.expo.repository.ExpoRepository
import team.startup.expo.domain.expo.service.GetPublicExpoService
import team.startup.expo.global.exception.ExpectedException

@Service
class GetPublicExpoServiceImpl(
    private val expoRepository: ExpoRepository,
) : GetPublicExpoService {
    @Transactional(readOnly = true)
    override fun execute(expoId: String): PublicExpoResponse {
        val expo = expoRepository.findByIdOrNull(expoId)
        if (expo == null || expo.deletingAt != null) throw ExpectedException(HttpStatus.NOT_FOUND, "박람회를 찾지 못 했습니다.")

        return PublicExpoResponse(
            title = expo.title,
            description = expo.description,
            location = expo.location,
            startedDay = expo.startedDay,
            finishedDay = expo.finishedDay,
        )
    }
}
