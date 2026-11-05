package team.startup.expo.domain.expo.service.impl

import org.springframework.stereotype.Service
import team.startup.expo.domain.expo.presentation.dto.response.AdminApplicationCancelAccessResponse
import team.startup.expo.domain.expo.repository.AdminApplicationCancelAccessRepository
import team.startup.expo.domain.expo.service.AdminAccessSupport
import team.startup.expo.domain.expo.service.GetAdminApplicationCancelAccessService

@Service
class GetAdminApplicationCancelAccessServiceImpl(
    private val admins: AdminAccessSupport,
    private val grants: AdminApplicationCancelAccessRepository,
) : GetAdminApplicationCancelAccessService {
    override fun execute(
        expoId: String,
        authorization: String?,
    ): AdminApplicationCancelAccessResponse {
        val adminId = admins.authenticate(authorization)
        admins.requireGrant(expoId, "신청 취소 권한이 없습니다.") {
            grants.existsByExpoIdAndAdminId(expoId, adminId)
        }
        return AdminApplicationCancelAccessResponse(expoId, adminId)
    }
}
