package team.startup.expo.domain.expo.repository

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import team.startup.expo.domain.expo.entity.StandardRegistration
import team.startup.expo.domain.expo.entity.StandardRegistrationId

interface StandardRegistrationRepository : JpaRepository<StandardRegistration, StandardRegistrationId> {
    @Modifying(flushAutomatically = true)
    @Query(
        "INSERT INTO tb_standard_registration(expo_id,participant_id) VALUES (:expoId,:participantId) ON CONFLICT DO NOTHING",
        nativeQuery = true,
    )
    fun insert(
        expoId: String,
        participantId: Long,
    ): Int
}
