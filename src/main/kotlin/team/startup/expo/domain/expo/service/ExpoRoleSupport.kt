package team.startup.expo.domain.expo.service

import org.springframework.beans.factory.annotation.Value
import org.springframework.dao.DataAccessException
import org.springframework.data.repository.findByIdOrNull
import org.springframework.http.HttpStatus
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import org.springframework.stereotype.Component
import team.startup.expo.domain.expo.entity.ExpoMemberRole
import team.startup.expo.domain.expo.repository.ExpoMemberRepository
import team.startup.expo.domain.expo.repository.ExpoRepository
import team.startup.expo.global.exception.ExpectedException

enum class ExpoPermission { APPLICATION_READ, STATS_READ, CHECKIN }

enum class ExpoAccess { READ, WRITE, OWNER }

data class ExpoAdmin(
    val id: Long,
    val superAdmin: Boolean,
)

@Component
class ExpoRoleSupport(
    private val members: ExpoMemberRepository,
    private val expos: ExpoRepository,
    private val authority: AdminAuthorityClient,
    @Value("\${expo.member-roles.enabled:false}") val enabled: Boolean,
) {
    fun current(): ExpoAdmin {
        val jwt =
            (SecurityContextHolder.getContext().authentication as? JwtAuthenticationToken)?.token
                ?: throw ExpectedException(HttpStatus.UNAUTHORIZED, "인증이 필요합니다.")
        val id =
            jwt.subject?.toLongOrNull()?.takeIf { it > 0 }
                ?: throw ExpectedException(HttpStatus.UNAUTHORIZED, "인증이 필요합니다.")
        if (jwt.getClaimAsString("role") != "ROLE_ADMIN") forbidden()
        return ExpoAdmin(id, authority.isSuperAdmin("Bearer ${jwt.tokenValue}"))
    }

    fun require(
        expoId: String,
        access: ExpoAccess,
        admin: ExpoAdmin = current(),
        force: Boolean = false,
    ) {
        try {
            val expo =
                expos.findByIdOrNull(expoId)
                    ?: throw ExpectedException(HttpStatus.NOT_FOUND, "박람회를 찾지 못 했습니다.")
            if (expo.deletingAt != null) throw ExpectedException(HttpStatus.SERVICE_UNAVAILABLE, "삭제 중인 박람회입니다.")
            if ((!enabled && !force) || admin.superAdmin) return
            val role = role(expoId, admin.id) ?: forbidden()
            if (!allows(role, access)) forbidden()
        } catch (exception: DataAccessException) {
            throw ExpectedException(HttpStatus.SERVICE_UNAVAILABLE, "행사 권한을 확인할 수 없습니다.")
        }
    }

    fun role(
        expoId: String,
        adminId: Long,
    ): ExpoMemberRole? = members.findByExpoIdAndAdminId(expoId, adminId)?.role

    fun visibleRoles(): Map<String, ExpoMemberRole>? {
        if (!enabled) return null
        val admin = current()
        if (admin.superAdmin) return null
        return members.findAllByAdminId(admin.id).associate { it.expoId to it.role }
    }

    fun currentRoles(): Map<String, ExpoMemberRole> {
        val id =
            (SecurityContextHolder.getContext().authentication as? JwtAuthenticationToken)?.token?.subject?.toLongOrNull()
                ?: return emptyMap()
        return members.findAllByAdminId(id).associate { it.expoId to it.role }
    }

    companion object {
        fun allows(
            role: ExpoMemberRole,
            access: ExpoAccess,
        ): Boolean =
            when (access) {
                ExpoAccess.READ -> true
                ExpoAccess.WRITE -> role != ExpoMemberRole.VIEWER
                ExpoAccess.OWNER -> role == ExpoMemberRole.OWNER
            }

        private fun forbidden(): Nothing = throw ExpectedException(HttpStatus.FORBIDDEN, "접근 권한이 없습니다.")
    }
}
