package team.startup.expo.domain.expo.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.util.UUID

@Entity
@Table(name = "tb_program_change")
class ProgramChange(
    @field:Id
    @field:Column(name = "expo_id", length = 36)
    val expoId: String,
    @field:Column(name = "change_id", nullable = false)
    val changeId: UUID = UUID.randomUUID(),
    @field:Column(name = "command_json", nullable = false, columnDefinition = "TEXT")
    val commandJson: String,
    @field:Column(name = "payload_json", nullable = false, columnDefinition = "TEXT")
    val payloadJson: String,
)
