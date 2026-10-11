package team.startup.expo.domain.training.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
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
import java.util.UUID

@Entity
@Table(name = "tb_training_program")
class TrainingProgram(
    @field:Id
    @field:GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @field:Column(nullable = false, length = 50)
    val title: String,

    @field:Column(name = "started_at", nullable = false, length = 20)
    val startedAt: String,

    @field:Column(name = "ended_at", nullable = false, length = 20)
    val endedAt: String,

    @field:Enumerated(EnumType.STRING)
    @field:Column(nullable = false, length = 255)
    val category: Category,

    @field:ManyToOne(fetch = FetchType.EAGER)
    @field:JoinColumn(name = "expo_id")
    @field:OnDelete(action = OnDeleteAction.CASCADE)
    val expo: Expo?,
    @field:Column(name = "group_id", nullable = false)
    val groupId: UUID = UUID.randomUUID(),

    @field:Column(name = "policy_json", columnDefinition = "TEXT")
    val policyJson: String? = null,

    @field:Column(nullable = false)
    val revision: Long = 1,
)
