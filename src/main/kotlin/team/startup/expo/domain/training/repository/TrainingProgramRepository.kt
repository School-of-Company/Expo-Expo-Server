package team.startup.expo.domain.training.repository

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import team.startup.expo.domain.expo.entity.Expo
import team.startup.expo.domain.training.entity.TrainingProgram

interface TrainingProgramRepository : JpaRepository<TrainingProgram, Long> {
    fun existsByIdInAndPolicyJsonIsNotNull(ids: List<Long>): Boolean

    fun findByExpo(expo: Expo): List<TrainingProgram>

    fun findByExpoIdOrderByIdAsc(expoId: String): List<TrainingProgram>

    fun findAllByIdIn(ids: List<Long>): List<TrainingProgram>

    fun findAllByIdInAndExpoId(
        ids: List<Long>,
        expoId: String,
    ): List<TrainingProgram>

    @Query("select program.expo.id from TrainingProgram program where program.id = :id")
    fun findExpoIdById(id: Long): String?

    fun existsByExpoIdAndPolicyJsonIsNotNull(expoId: String): Boolean

    fun findByGroupId(groupId: java.util.UUID): List<TrainingProgram>
}
