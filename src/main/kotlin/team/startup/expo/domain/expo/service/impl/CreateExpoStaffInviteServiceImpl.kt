package team.startup.expo.domain.expo.service.impl

import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import team.startup.expo.domain.expo.entity.ExpoStaffInvite
import team.startup.expo.domain.expo.presentation.dto.request.CreateExpoStaffInviteRequest
import team.startup.expo.domain.expo.presentation.dto.response.ExpoStaffInviteResponse
import team.startup.expo.domain.expo.repository.ExpoStaffInviteRepository
import team.startup.expo.domain.expo.service.CreateExpoStaffInviteService
import team.startup.expo.domain.expo.service.ExpoMemberSupport
import team.startup.expo.domain.expo.service.ExpoRoleSupport
import team.startup.expo.domain.expo.service.StaffInviteSupport
import team.startup.expo.global.exception.ExpectedException
import java.time.Instant
import java.util.UUID

@Service
class CreateExpoStaffInviteServiceImpl(
    private val roles: ExpoRoleSupport,
    private val invites: ExpoStaffInviteRepository,
    private val support: ExpoMemberSupport,
    private val staff: StaffInviteSupport,
    transactions: PlatformTransactionManager,
) : CreateExpoStaffInviteService {
    private val writes = TransactionTemplate(transactions)

    override fun execute(
        expoId: String,
        request: CreateExpoStaffInviteRequest,
    ): ExpoStaffInviteResponse {
        val admin = roles.current()
        return requireNotNull(
            writes.execute {
                support.lockOwner(expoId, admin)
                if (!request.expiresAt.isAfter(Instant.now())) throw ExpectedException(HttpStatus.BAD_REQUEST, "만료 시각은 미래여야 합니다.")
                val secret = staff.newSecret()
                val invite = invites.save(ExpoStaffInvite(UUID.randomUUID(), expoId, request.label, staff.hash(secret), request.expiresAt))
                ExpoStaffInviteResponse(invite.id, invite.expoId, invite.label, invite.expiresAt, invite.revokedAt, secret)
            },
        )
    }
}
