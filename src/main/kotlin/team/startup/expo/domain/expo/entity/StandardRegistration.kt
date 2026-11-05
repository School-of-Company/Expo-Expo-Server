package team.startup.expo.domain.expo.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.IdClass
import jakarta.persistence.Table
import java.io.Serializable
import java.time.Instant

data class StandardRegistrationId(
    val expoId: String = "",
    val participantId: Long = 0,
) : Serializable

@Entity
@IdClass(StandardRegistrationId::class)
@Table(name = "tb_standard_registration")
class StandardRegistration(
    @field:Id
    @field:Column(name = "expo_id", length = 36, nullable = false)
    val expoId: String,
    @field:Id
    @field:Column(name = "participant_id", nullable = false)
    val participantId: Long,
    @field:Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    val createdAt: Instant? = null,
)
