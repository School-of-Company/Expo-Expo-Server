package team.startup.expo.domain.expo.service.impl

import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import team.startup.expo.domain.expo.entity.Expo
import team.startup.expo.domain.expo.entity.ExpoMemberRole
import team.startup.expo.domain.expo.presentation.dto.request.ExpoPageRequest
import team.startup.expo.domain.expo.presentation.dto.response.ExpoPageResponse
import team.startup.expo.domain.expo.presentation.dto.response.ExpoSummaryResponse
import team.startup.expo.domain.expo.repository.ExpoRepository
import team.startup.expo.domain.expo.service.ExpoRoleSupport
import team.startup.expo.domain.expo.service.GetExpoListService

@Service
class GetExpoListServiceImpl(
    private val expoRepository: ExpoRepository,
    private val roles: ExpoRoleSupport,
) : GetExpoListService {
    @Transactional(readOnly = true)
    override fun execute(request: ExpoPageRequest): Any {
        val visible = roles.visibleRoles()
        val myRoles = if (roles.enabled) visible ?: roles.currentRoles() else emptyMap()
        val sort = Sort.by(Sort.Direction.DESC, "id")
        if (!request.isPaged) {
            val expos = if (visible == null) expoRepository.findAll(sort) else expoRepository.findAllByIdIn(visible.keys, sort)
            return expos.map { it.toSummary(myRoles[it.id]) }
        }
        val pageable = PageRequest.of(request.pageNumber, request.pageSize, sort)
        val result = if (visible == null) expoRepository.findAll(pageable) else expoRepository.findAllByIdIn(visible.keys, pageable)
        return ExpoPageResponse(
            content = result.content.map { it.toSummary(myRoles[it.id]) },
            page = result.number,
            size = result.size,
            totalElements = result.totalElements,
            totalPages = result.totalPages,
            hasNext = result.hasNext(),
        )
    }

    private fun Expo.toSummary(role: ExpoMemberRole?): ExpoSummaryResponse =
        ExpoSummaryResponse(
            id = id,
            title = title,
            description = description,
            startedDay = startedDay,
            finishedDay = finishedDay,
            coverImage = coverImage,
            myRole = role,
        )
}
