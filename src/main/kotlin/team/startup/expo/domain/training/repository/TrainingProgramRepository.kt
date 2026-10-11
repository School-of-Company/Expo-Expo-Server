package team.startup.expo.domain.training.repository

import org.springframework.data.jpa.repository.JpaRepository
import team.startup.expo.domain.expo.entity.Expo
import team.startup.expo.domain.training.entity.TrainingProgram

interface TrainingProgramRepository : JpaRepository<TrainingProgram, Long> {
    fun deleteByExpo(expo: Expo)

    fun findByExpo(expo: Expo): List<TrainingProgram>

    fun findByExpoIdOrderByIdAsc(expoId: String): List<TrainingProgram>

    fun findAllByIdIn(ids: List<Long>): List<TrainingProgram>

    fun existsByExpoIdAndPolicyJsonIsNotNull(expoId: String): Boolean

    fun findByGroupId(groupId: java.util.UUID): List<TrainingProgram>
}
