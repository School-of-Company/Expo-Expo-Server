package team.startup.expo.domain.expo.presentation.dto.response

import team.startup.expo.domain.expo.entity.ExpoMemberRole
import java.time.Instant
import java.util.UUID

data class ExpoMemberResponse(
    val adminId: Long,
    val role: ExpoMemberRole,
)

data class ExpoStaffInviteResponse(
    val inviteId: UUID,
    val expoId: String,
    val label: String,
    val expiresAt: Instant,
    val revokedAt: Instant?,
    val secret: String? = null,
)

data class ExchangeStaffInviteResponse(
    val inviteId: UUID,
    val expoId: String,
    val expiresAt: Instant,
)
