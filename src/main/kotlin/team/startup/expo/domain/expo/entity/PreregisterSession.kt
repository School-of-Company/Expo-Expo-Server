package team.startup.expo.domain.expo.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import org.hibernate.annotations.OnDelete
import org.hibernate.annotations.OnDeleteAction
import java.time.Instant

@Entity
@Table(name = "tb_preregister_session")
class PreregisterSession(
    @field:Id
    @field:GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @field:ManyToOne(fetch = FetchType.LAZY)
    @field:JoinColumn(name = "expo_id", nullable = false)
    @field:OnDelete(action = OnDeleteAction.CASCADE)
    val expo: Expo,

    @field:Column(nullable = false, length = 50)
    val title: String,

    @field:Column(name = "started_at", nullable = false)
    val startedAt: Instant,

    @field:Column(name = "ended_at", nullable = false)
    val endedAt: Instant,

    @field:Column(nullable = false, columnDefinition = "TEXT")
    val place: String,

    @field:Column(nullable = false)
    val capacity: Int,

    @field:Column(name = "waiting_capacity", nullable = false)
    val waitingCapacity: Int,

    @field:Column(nullable = false)
    val closed: Boolean,

    @field:Column(nullable = false)
    val revision: Long = 1,
)
