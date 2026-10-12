package team.startup.expo.domain.expo.service.impl

import org.springframework.dao.DataAccessException
import org.springframework.data.repository.findByIdOrNull
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import team.startup.expo.domain.expo.presentation.dto.request.AdminChangeHistoryRequest
import team.startup.expo.domain.expo.presentation.dto.response.AdminChangeHistoryPage
import team.startup.expo.domain.expo.repository.AdminChangeHistoryRepository
import team.startup.expo.domain.expo.repository.ExpoRepository
import team.startup.expo.domain.expo.service.AdminAuthorityClient
import team.startup.expo.domain.expo.service.GetAdminChangeHistoryService
import team.startup.expo.global.exception.ExpectedException

@Service
class GetAdminChangeHistoryServiceImpl(
    private val authority: AdminAuthorityClient,
    private val expos: ExpoRepository,
    private val history: AdminChangeHistoryRepository,
) : GetAdminChangeHistoryService {
    @Transactional(readOnly = true, isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    override fun execute(
        expoId: String,
        authorization: String,
        request: AdminChangeHistoryRequest,
    ): AdminChangeHistoryPage {
        authority.requireCurrentAdmin(authorization)
        val expo =
            try {
                expos.findByIdOrNull(expoId)
            } catch (exception: DataAccessException) {
                throw ExpectedException(HttpStatus.SERVICE_UNAVAILABLE, "행사 권한을 확인할 수 없습니다.")
            } ?: throw ExpectedException(HttpStatus.NOT_FOUND, "박람회를 찾지 못 했습니다.")
        if (expo.deletingAt != null) throw ExpectedException(HttpStatus.SERVICE_UNAVAILABLE, "삭제 중인 박람회입니다.")
        if (request.from != null && request.to != null && !request.from.isBefore(request.to)) {
            throw ExpectedException(HttpStatus.BAD_REQUEST, "조회 시작은 종료보다 빨라야 합니다.")
        }
        return history.page(expoId, request)
    }
}
