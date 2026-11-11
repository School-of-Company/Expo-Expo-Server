package team.startup.expo.domain.expo.service.impl

import org.springframework.dao.DataAccessException
import org.springframework.data.repository.findByIdOrNull
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import team.startup.expo.domain.expo.presentation.dto.response.AdminExpoAccessResponse
import team.startup.expo.domain.expo.repository.ExpoRepository
import team.startup.expo.domain.expo.service.AdminAccessSupport
import team.startup.expo.domain.expo.service.AdminAuthorityClient
import team.startup.expo.domain.expo.service.ExpoAccess
import team.startup.expo.domain.expo.service.ExpoAdmin
import team.startup.expo.domain.expo.service.ExpoPermission
import team.startup.expo.domain.expo.service.ExpoRoleSupport
import team.startup.expo.domain.expo.service.GetAdminExpoAccessService
import team.startup.expo.domain.expo.service.StaffInviteSupport
import team.startup.expo.global.exception.ExpectedException
import java.util.UUID

@Service
class GetAdminExpoAccessServiceImpl(
    private val admins: AdminAccessSupport,
    private val expos: ExpoRepository,
    private val roles: ExpoRoleSupport,
    private val authority: AdminAuthorityClient,
    private val staff: StaffInviteSupport,
) : GetAdminExpoAccessService {
    override fun execute(
        expoId: String,
        authorization: String?,
        permission: ExpoPermission,
    ): AdminExpoAccessResponse {
        val jwt = admins.decode(authorization)
        if (jwt.getClaimAsString("role") == "ROLE_STAFF") {
            if (jwt.getClaimAsString("expoId") != expoId || permission == ExpoPermission.APPLICATION_READ) {
                throw ExpectedException(HttpStatus.FORBIDDEN, "접근 권한이 없습니다.")
            }
            val inviteId =
                runCatching { UUID.fromString(jwt.subject?.removePrefix("staff:")) }.getOrNull()
                    ?: throw ExpectedException(HttpStatus.UNAUTHORIZED, "인증이 필요합니다.")
            admins.requireGrant(expoId, "접근 권한이 없습니다.") {
                staff.requireValid(inviteId, expoId)
                true
            }
            return AdminExpoAccessResponse(expoId, null, permission.name, "EXPO_STAFF_ACCESS_V1", "EXPO_STAFF", inviteId)
        }
        val adminId = admins.authenticate(authorization)
        if (roles.enabled) {
            roles.require(
                expoId,
                if (permission == ExpoPermission.STATS_READ) ExpoAccess.READ else ExpoAccess.WRITE,
                ExpoAdmin(adminId, authority.isSuperAdmin(requireNotNull(authorization))),
            )
        }
        val expo =
            try {
                expos.findByIdOrNull(expoId)
            } catch (exception: DataAccessException) {
                throw ExpectedException(HttpStatus.SERVICE_UNAVAILABLE, "행사 권한을 확인할 수 없습니다.")
            } ?: throw ExpectedException(HttpStatus.NOT_FOUND, "박람회를 찾지 못 했습니다.")
        if (expo.deletingAt != null) throw ExpectedException(HttpStatus.SERVICE_UNAVAILABLE, "삭제 중인 박람회입니다.")
        return AdminExpoAccessResponse(expoId, adminId, permission.name)
    }
}
