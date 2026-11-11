package team.startup.expo.domain.expo.service

import org.springframework.stereotype.Component
import team.startup.expo.domain.expo.entity.ExpoMemberRole

@Component
class ExpoMemberAccessSupport(
    private val roles: ExpoRoleSupport,
    private val authority: AdminAuthorityClient,
) {
    fun manager(
        expoId: String,
        adminId: Long,
        authorization: String,
    ): Boolean = authority.isSuperAdmin(authorization) || roles.role(expoId, adminId) in setOf(ExpoMemberRole.OWNER, ExpoMemberRole.MANAGER)

    fun creator(
        expoId: String,
        adminId: Long,
    ): Boolean = roles.role(expoId, adminId) == ExpoMemberRole.OWNER
}
