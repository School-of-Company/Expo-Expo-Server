package team.startup.expo.domain.expo.service.impl

import org.springframework.data.repository.findByIdOrNull
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import team.startup.expo.domain.expo.presentation.dto.response.ExpoDetailResponse
import team.startup.expo.domain.expo.repository.ExpoRepository
import team.startup.expo.domain.expo.service.GetExpoDetailService
import team.startup.expo.global.exception.ExpectedException

@Service
class GetExpoDetailServiceImpl(
    private val expoRepository: ExpoRepository,
) : GetExpoDetailService {
    @Transactional(readOnly = true)
    override fun execute(expoId: String): ExpoDetailResponse {
        val expo =
            expoRepository.findByIdOrNull(expoId)
                ?: throw ExpectedException(HttpStatus.NOT_FOUND, "박람회를 찾을 수 없습니다.")

        return ExpoDetailResponse(
            title = expo.title,
            description = expo.description,
            startedDay = expo.startedDay,
            finishedDay = expo.finishedDay,
            location = expo.location,
            coverImage = expo.coverImage,
            x = expo.x,
            y = expo.y,
        )
    }
}
