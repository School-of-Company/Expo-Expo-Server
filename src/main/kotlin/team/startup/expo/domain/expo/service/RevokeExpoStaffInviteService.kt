package team.startup.expo.domain.expo.service

import java.util.UUID

interface RevokeExpoStaffInviteService {
    fun execute(
        expoId: String,
        inviteId: UUID,
    ): Unit
}
