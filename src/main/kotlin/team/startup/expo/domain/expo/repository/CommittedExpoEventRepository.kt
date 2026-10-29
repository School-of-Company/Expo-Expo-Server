package team.startup.expo.domain.expo.repository

import jakarta.persistence.EntityManager
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import team.startup.expo.domain.expo.entity.ExpoEventOutbox
import team.startup.expo.domain.expo.repository.ExpoEventOutboxRepository.Publication
import java.util.UUID

@Repository
class CommittedExpoEventRepository(
    private val entityManager: EntityManager,
) {
    // 릴레이와 같은 행 잠금·30초 lease·선행 PENDING 조건으로 특정 행만 선점한다.
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun claim(eventId: String): Publication? {
        val event =
            entityManager
                .createNativeQuery(
                    """SELECT event.* FROM tb_expo_event_outbox AS event
                    WHERE event.event_id = :eventId AND event.state = 'PENDING'
                    AND event.next_attempt_at <= CURRENT_TIMESTAMP
                    AND (event.publish_until IS NULL OR event.publish_until <= CURRENT_TIMESTAMP)
                    AND NOT EXISTS (SELECT 1 FROM tb_expo_event_outbox AS earlier
                        WHERE earlier.expo_id = event.expo_id AND earlier.state = 'PENDING' AND earlier.sequence < event.sequence)
                    FOR UPDATE SKIP LOCKED""",
                    ExpoEventOutbox::class.java,
                ).setParameter("eventId", eventId)
                .resultList
                .firstOrNull() as ExpoEventOutbox? ?: return null
        val token = UUID.randomUUID().toString()
        entityManager
            .createNativeQuery(
                """UPDATE tb_expo_event_outbox SET publish_token = :token,
                publish_until = CURRENT_TIMESTAMP + INTERVAL '30 seconds' WHERE sequence = :sequence""",
            ).setParameter("token", token)
            .setParameter("sequence", event.sequence)
            .executeUpdate()
        return Publication(event.sequence, event.eventId, token, event.expoId, event.snapshot, event.createdAt)
    }
}
