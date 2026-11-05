package team.startup.expo.domain.expo.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.IdClass
import jakarta.persistence.Table
import java.io.Serializable
import java.time.Instant
import java.util.UUID

data class ProgramFormChangeId(
    val formId: UUID = UUID(0, 0),
    val changeId: UUID = UUID(0, 0),
) : Serializable

@Entity
@IdClass(ProgramFormChangeId::class)
@Table(name = "tb_program_form_change")
class ProgramFormChange(
    @field:Id
    @field:Column(name = "form_id", nullable = false)
    val formId: UUID,
    @field:Id
    @field:Column(name = "change_id", nullable = false)
    val changeId: UUID,
    @field:Column(name = "request_json", columnDefinition = "TEXT")
    val request: String?,
    @field:Column(name = "response_json", columnDefinition = "TEXT")
    val response: String?,
    @field:Column(name = "expires_at")
    val expiresAt: Instant?,
    @field:Column(nullable = false)
    val released: Boolean,
)
