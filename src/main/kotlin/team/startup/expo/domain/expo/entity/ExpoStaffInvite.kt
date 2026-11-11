package team.startup.expo.domain.expo.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "tb_expo_staff_invite")
class ExpoStaffInvite(
    @field:Id
    val id: UUID,
    @field:Column(nullable = false, length = 36)
    val expoId: String,
    @field:Column(nullable = false, length = 100)
    val label: String,
    @field:Column(nullable = false, length = 64)
    val secretHash: String,
    @field:Column(nullable = false)
    val expiresAt: Instant,
    var revokedAt: Instant? = null,
) {
    fun isValid(now: Instant = Instant.now()): Boolean = revokedAt == null && expiresAt.isAfter(now)
}
