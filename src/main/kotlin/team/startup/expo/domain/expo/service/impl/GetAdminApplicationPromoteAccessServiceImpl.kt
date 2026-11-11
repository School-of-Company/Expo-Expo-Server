package team.startup.expo.domain.expo.service.impl

import org.springframework.stereotype.Service
import team.startup.expo.domain.expo.presentation.dto.response.AdminApplicationPromoteAccessResponse
import team.startup.expo.domain.expo.repository.AdminApplicationPromoteAccessRepository
import team.startup.expo.domain.expo.service.AdminAccessSupport
import team.startup.expo.domain.expo.service.ExpoMemberAccessSupport
import team.startup.expo.domain.expo.service.ExpoRoleSupport
import team.startup.expo.domain.expo.service.GetAdminApplicationPromoteAccessService

@Service
class GetAdminApplicationPromoteAccessServiceImpl(
    private val admins: AdminAccessSupport,
    private val roles: ExpoRoleSupport,
    private val memberAccess: ExpoMemberAccessSupport,
    private val grants: AdminApplicationPromoteAccessRepository,
) : GetAdminApplicationPromoteAccessService {
    override fun execute(
        expoId: String,
        authorization: String?,
    ): AdminApplicationPromoteAccessResponse {
        val adminId = admins.authenticate(authorization)
        admins.requireGrant(expoId, "수동 확정 권한이 없습니다.") {
            if (roles.enabled) {
                memberAccess.manager(expoId, adminId, requireNotNull(authorization))
            } else {
                grants.existsByExpoIdAndAdminId(expoId, adminId) || memberAccess.creator(expoId, adminId)
            }
        }
        return AdminApplicationPromoteAccessResponse(expoId, adminId)
    }
}
