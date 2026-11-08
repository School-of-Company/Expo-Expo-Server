package team.startup.expo.domain.expo.service.impl

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import team.startup.expo.domain.expo.presentation.dto.response.ExpoStaffInviteResponse
import team.startup.expo.domain.expo.repository.ExpoStaffInviteRepository
import team.startup.expo.domain.expo.service.ExpoAccess
import team.startup.expo.domain.expo.service.ExpoRoleSupport
import team.startup.expo.domain.expo.service.GetExpoStaffInviteListService

@Service
class GetExpoStaffInviteListServiceImpl(
    private val roles: ExpoRoleSupport,
    private val invites: ExpoStaffInviteRepository,
) : GetExpoStaffInviteListService {
    @Transactional(readOnly = true)
    override fun execute(expoId: String): List<ExpoStaffInviteResponse> {
        roles.require(expoId, ExpoAccess.OWNER, force = true)
        return invites.findAllByExpoIdOrderByExpiresAtDesc(expoId).map {
            ExpoStaffInviteResponse(it.id, it.expoId, it.label, it.expiresAt, it.revokedAt)
        }
    }
}
