package team.startup.expo.domain.expo.service.impl

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import team.startup.expo.domain.expo.presentation.dto.response.ExpoSummaryResponse
import team.startup.expo.domain.expo.repository.ExpoRepository
import team.startup.expo.domain.expo.service.GetExpoListService

@Service
class GetExpoListServiceImpl(
    private val expoRepository: ExpoRepository,
) : GetExpoListService {
    @Transactional(readOnly = true)
    override fun execute(): List<ExpoSummaryResponse> =
        expoRepository.findAll().map { expo ->
            ExpoSummaryResponse(
                id = expo.id,
                title = expo.title,
                description = expo.description,
                startedDay = expo.startedDay,
                finishedDay = expo.finishedDay,
                coverImage = expo.coverImage,
            )
        }
}
