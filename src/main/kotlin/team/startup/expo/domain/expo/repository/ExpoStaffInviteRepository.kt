package team.startup.expo.domain.expo.repository

import org.springframework.data.jpa.repository.JpaRepository
import team.startup.expo.domain.expo.entity.ExpoStaffInvite
import java.util.UUID

interface ExpoStaffInviteRepository : JpaRepository<ExpoStaffInvite, UUID> {
    fun findAllByExpoIdOrderByExpiresAtDesc(expoId: String): List<ExpoStaffInvite>

    fun findBySecretHash(secretHash: String): ExpoStaffInvite?

    fun findByIdAndExpoId(
        id: UUID,
        expoId: String,
    ): ExpoStaffInvite?
}
