package team.startup.expo.domain.expo.service.impl

import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import team.startup.expo.domain.expo.repository.ExpoStaffInviteRepository
import team.startup.expo.domain.expo.service.ExpoMemberSupport
import team.startup.expo.domain.expo.service.ExpoRoleSupport
import team.startup.expo.domain.expo.service.RevokeExpoStaffInviteService
import team.startup.expo.global.exception.ExpectedException
import java.time.Instant
import java.util.UUID

@Service
class RevokeExpoStaffInviteServiceImpl(
    private val roles: ExpoRoleSupport,
    private val invites: ExpoStaffInviteRepository,
    private val support: ExpoMemberSupport,
    transactions: PlatformTransactionManager,
) : RevokeExpoStaffInviteService {
    private val writes = TransactionTemplate(transactions)

    override fun execute(
        expoId: String,
        inviteId: UUID,
    ) {
        val admin = roles.current()
        writes.executeWithoutResult {
            support.lockOwner(expoId, admin)
            val invite = invites.findByIdAndExpoId(inviteId, expoId) ?: throw ExpectedException(HttpStatus.NOT_FOUND, "스태프 링크를 찾지 못 했습니다.")
            if (invite.revokedAt == null) invite.revokedAt = Instant.now()
        }
    }
}
