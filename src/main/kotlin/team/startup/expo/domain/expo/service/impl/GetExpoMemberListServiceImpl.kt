package team.startup.expo.domain.expo.service.impl

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import team.startup.expo.domain.expo.presentation.dto.response.ExpoMemberResponse
import team.startup.expo.domain.expo.repository.ExpoMemberRepository
import team.startup.expo.domain.expo.service.ExpoAccess
import team.startup.expo.domain.expo.service.ExpoRoleSupport
import team.startup.expo.domain.expo.service.GetExpoMemberListService

@Service
class GetExpoMemberListServiceImpl(
    private val roles: ExpoRoleSupport,
    private val members: ExpoMemberRepository,
) : GetExpoMemberListService {
    @Transactional(readOnly = true)
    override fun execute(expoId: String): List<ExpoMemberResponse> {
        roles.require(expoId, ExpoAccess.OWNER, force = true)
        return members.findAllByExpoIdOrderByAdminId(expoId).map { ExpoMemberResponse(it.adminId, it.role) }
    }
}
