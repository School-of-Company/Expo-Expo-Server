package team.startup.expo.domain.expo.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table

@Entity
@Table(name = "tb_admin_application_cancel_access")
class AdminApplicationCancelAccess(
    @field:Column(name = "expo_id", nullable = false, length = 36)
    val expoId: String,
    @field:Column(name = "admin_id", nullable = false)
    val adminId: Long,
    @field:Id
    @field:GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,
)
