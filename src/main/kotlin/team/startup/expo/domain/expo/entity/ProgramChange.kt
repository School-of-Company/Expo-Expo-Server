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
    @field:Column(name = "admin_id")
    val adminId: Long? = null,
    // 로컬 반영은 커밋됐고 삭제한 프로그램의 원격 정리만 남았다.
    @field:Column(name = "applied", nullable = false)
    var applied: Boolean = false,
)
