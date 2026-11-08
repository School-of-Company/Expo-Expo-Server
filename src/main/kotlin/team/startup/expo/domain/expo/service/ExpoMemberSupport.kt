package team.startup.expo.domain.expo.service

import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import team.startup.expo.domain.expo.entity.ExpoMember
import team.startup.expo.domain.expo.entity.ExpoMemberRole
import team.startup.expo.domain.expo.repository.ExpoMemberRepository
import team.startup.expo.domain.expo.repository.ExpoRepository
import team.startup.expo.global.exception.ExpectedException

@Component
class ExpoMemberSupport(
    private val expos: ExpoRepository,
    private val roles: ExpoRoleSupport,
    private val members: ExpoMemberRepository,
) {
    fun lockOwner(
        expoId: String,
        admin: ExpoAdmin,
    ) {
        expos.findLockedById(expoId) ?: throw ExpectedException(HttpStatus.NOT_FOUND, "박람회를 찾지 못 했습니다.")
        roles.require(expoId, ExpoAccess.OWNER, admin, force = true)
    }

    fun member(
        expoId: String,
        adminId: Long,
    ): ExpoMember =
        members.findByExpoIdAndAdminId(expoId, adminId)
            ?: throw ExpectedException(HttpStatus.NOT_FOUND, "운영진을 찾지 못 했습니다.")

    fun validateChange(
        expoId: String,
        previous: ExpoMemberRole?,
        next: ExpoMemberRole?,
    ) {
        val owners = members.countByExpoIdAndRole(expoId, ExpoMemberRole.OWNER)
        if (previous != ExpoMemberRole.OWNER && next == ExpoMemberRole.OWNER && owners >= 3) {
            throw ExpectedException(HttpStatus.CONFLICT, "OWNER는 최대 3명입니다.")
        }
        if (previous == ExpoMemberRole.OWNER && next != ExpoMemberRole.OWNER && owners <= 1) {
            throw ExpectedException(HttpStatus.CONFLICT, "마지막 OWNER는 내보내거나 강등할 수 없습니다.")
        }
    }
}
