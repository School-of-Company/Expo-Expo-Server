package team.startup.expo.domain.expo.service.impl

import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import team.startup.expo.domain.expo.entity.Expo
import team.startup.expo.domain.expo.presentation.dto.request.ExpoPageRequest
import team.startup.expo.domain.expo.presentation.dto.response.ExpoPageResponse
import team.startup.expo.domain.expo.presentation.dto.response.ExpoSummaryResponse
import team.startup.expo.domain.expo.repository.ExpoRepository
import team.startup.expo.domain.expo.service.GetExpoListService

@Service
class GetExpoListServiceImpl(
    private val expoRepository: ExpoRepository,
) : GetExpoListService {
    @Transactional(readOnly = true)
    override fun execute(request: ExpoPageRequest): Any {
        if (!request.isPaged) return expoRepository.findAll(Sort.by(Sort.Direction.DESC, "id")).map { it.toSummary() }
        val result = expoRepository.findAll(PageRequest.of(request.pageNumber, request.pageSize, Sort.by(Sort.Direction.DESC, "id")))
        return ExpoPageResponse(
            content = result.content.map { it.toSummary() },
            page = result.number,
            size = result.size,
            totalElements = result.totalElements,
            totalPages = result.totalPages,
            hasNext = result.hasNext(),
        )
    }

    private fun Expo.toSummary(): ExpoSummaryResponse =
        ExpoSummaryResponse(
            id = id,
            title = title,
            description = description,
            startedDay = startedDay,
            finishedDay = finishedDay,
            coverImage = coverImage,
        )
}
