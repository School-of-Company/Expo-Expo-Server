package team.startup.expo.domain.expo.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table

enum class ExpoMemberRole { OWNER, MANAGER, VIEWER }

@Entity
@Table(name = "tb_expo_member")
class ExpoMember(
    @field:Column(nullable = false, length = 36)
    val expoId: String,
    @field:Column(nullable = false)
    val adminId: Long,
    @field:Enumerated(EnumType.STRING)
    @field:Column(nullable = false, length = 16)
    var role: ExpoMemberRole,
    @field:Id
    @field:GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,
)
