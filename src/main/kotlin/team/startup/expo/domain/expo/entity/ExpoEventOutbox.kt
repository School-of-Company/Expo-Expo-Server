package team.startup.expo.domain.expo.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.Immutable
import java.time.Instant

@Entity
@Immutable
@Table(name = "tb_expo_event_outbox")
class ExpoEventOutbox(
    @field:Id
    val sequence: Long,
    @field:Column(name = "event_id", length = 36, nullable = false)
    val eventId: String,
    @field:Column(name = "expo_id", length = 36, nullable = false)
    val expoId: String,
    @field:Column(columnDefinition = "TEXT")
    val snapshot: String?,
    @field:Column(name = "created_at", nullable = false)
    val createdAt: Instant,
)
