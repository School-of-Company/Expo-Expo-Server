package team.startup.expo.domain.expo.service.impl

import org.springframework.data.repository.findByIdOrNull
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import team.startup.expo.domain.expo.presentation.dto.response.ExpoPeriodResponse
import team.startup.expo.domain.expo.repository.ExpoRepository
import team.startup.expo.domain.expo.service.GetExpoPeriodService
import team.startup.expo.global.exception.ExpectedException

@Service
class GetExpoPeriodServiceImpl(
    private val expoRepository: ExpoRepository,
) : GetExpoPeriodService {
    @Transactional(readOnly = true)
    override fun execute(expoId: String): ExpoPeriodResponse {
        val expo =
            expoRepository.findByIdOrNull(expoId)
                ?: throw ExpectedException(HttpStatus.NOT_FOUND, "박람회를 찾을 수 없습니다.")
        return ExpoPeriodResponse(title = expo.title, startedDay = expo.startedDay, finishedDay = expo.finishedDay)
    }
}
