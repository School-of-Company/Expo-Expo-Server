package team.startup.expo.domain.expo.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.Generated
import team.startup.expo.domain.expo.presentation.dto.request.AdminChangeTargetType
import team.startup.expo.domain.expo.presentation.dto.request.AdminChangeType
import java.time.Instant

@Entity
@Table(name = "tb_admin_change_history")
class AdminChangeHistory(
    @field:Column(name = "expo_id", length = 36, nullable = false)
    val expoId: String,
    @field:Enumerated(EnumType.STRING)
    @field:Column(length = 32, nullable = false)
    val type: AdminChangeType,
    @field:Enumerated(EnumType.STRING)
    @field:Column(name = "target_type", length = 32, nullable = false)
    val targetType: AdminChangeTargetType,
    @field:Column(name = "target_id", nullable = false)
    val targetId: Long,
    @field:Column(columnDefinition = "TEXT", nullable = false)
    val summary: String,
    @field:Column(name = "admin_id")
    val adminId: Long?,
    @field:Id
    @field:GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,
    @field:Column(name = "occurred_at", nullable = false, insertable = false, updatable = false)
    @field:Generated
    val occurredAt: Instant? = null,
)
