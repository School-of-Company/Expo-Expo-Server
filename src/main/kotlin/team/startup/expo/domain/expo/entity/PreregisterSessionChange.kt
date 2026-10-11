package team.startup.expo.domain.expo.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.util.UUID

@Entity
@Table(name = "tb_preregister_session_change")
class PreregisterSessionChange(
    @field:Id
    @field:Column(name = "session_id")
    val sessionId: Long,

    @field:Column(name = "next_revision", nullable = false)
    val nextRevision: Long,

    @field:Enumerated(EnumType.STRING)
    @field:Column(nullable = false, length = 6)
    val operation: PreregisterSessionChangeOperation,

    @field:Column(name = "definition_changed", nullable = false)
    val definitionChanged: Boolean,

    @field:Column(name = "definition_json", columnDefinition = "TEXT")
    val definitionJson: String?,

    @field:Column(name = "change_id", nullable = false)
    val changeId: UUID = UUID.randomUUID(),
)

enum class PreregisterSessionChangeOperation {
    UPDATE,
    DELETE,
}
