package team.startup.expo.domain.standard.entity

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
import team.startup.expo.domain.expo.entity.Expo

@Entity
@Table(name = "tb_standard_program")
class StandardProgram(
    @field:Id
    @field:GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @field:Column(nullable = false, length = 50)
    val title: String,

    @field:Column(name = "started_at", nullable = false, length = 20)
    val startedAt: String,

    @field:Column(name = "ended_at", nullable = false, length = 20)
    val endedAt: String,

    @field:ManyToOne(fetch = FetchType.LAZY)
    @field:JoinColumn(name = "expo_id")
    @field:OnDelete(action = OnDeleteAction.CASCADE)
    val expo: Expo?,
)
