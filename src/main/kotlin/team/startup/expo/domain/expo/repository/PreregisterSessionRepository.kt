package team.startup.expo.domain.expo.repository

import org.springframework.data.jpa.repository.JpaRepository
import team.startup.expo.domain.expo.entity.PreregisterSession

interface PreregisterSessionRepository : JpaRepository<PreregisterSession, Long> {
    fun findAllByExpoIdOrderByStartedAtAscIdAsc(expoId: String): List<PreregisterSession>

    fun findByIdAndExpoId(
        id: Long,
        expoId: String,
    ): PreregisterSession?
}
