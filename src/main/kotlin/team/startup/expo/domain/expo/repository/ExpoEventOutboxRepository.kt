package team.startup.expo.domain.expo.repository

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import team.startup.expo.domain.expo.entity.ExpoEventOutbox
import java.time.Instant
import java.util.UUID

interface ExpoEventOutboxRepository : JpaRepository<ExpoEventOutbox, Long> {
    @Modifying(flushAutomatically = true)
    @Query(
        "INSERT INTO tb_expo_event_outbox(event_id,expo_id,deleted,snapshot) VALUES (:eventId,:expoId,:deleted,:snapshot)",
        nativeQuery = true,
    )
    fun insertRow(
        eventId: String,
        expoId: String,
        deleted: Boolean,
        snapshot: String?,
    ): Int

    fun insert(
        expoId: String,
        snapshot: String?,
    ): String {
        val eventId = UUID.randomUUID().toString()
        insertRow(eventId, expoId, snapshot == null, snapshot)
        return eventId
    }

    @Query(
        "WITH candidate AS (SELECT event.sequence FROM tb_expo_event_outbox event WHERE " + CLAIMABLE +
            " ORDER BY event.sequence LIMIT 1 FOR UPDATE SKIP LOCKED) " +
            "UPDATE tb_expo_event_outbox event SET publish_token=:token, publish_until=CURRENT_TIMESTAMP + INTERVAL " + LEASE +
            " FROM candidate WHERE event.sequence=candidate.sequence RETURNING event.*",
        nativeQuery = true,
    )
    fun claimNext(token: String): List<ExpoEventOutbox>

    @Query(
        "WITH candidate AS (SELECT event.sequence FROM tb_expo_event_outbox event WHERE event.event_id=:eventId AND " + CLAIMABLE +
            " FOR UPDATE SKIP LOCKED) UPDATE tb_expo_event_outbox event SET publish_token=:token, " +
            "publish_until=CURRENT_TIMESTAMP + INTERVAL " + LEASE +
            " FROM candidate WHERE event.sequence=candidate.sequence RETURNING event.*",
        nativeQuery = true,
    )
    fun claimCommitted(
        eventId: String,
        token: String,
    ): List<ExpoEventOutbox>

    @Transactional
    fun claim(): Publication? {
        val token = UUID.randomUUID().toString()
        return claimNext(token).firstOrNull()?.let { Publication(it.sequence, it.eventId, token, it.expoId, it.snapshot, it.createdAt) }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun claim(eventId: String): Publication? {
        val token = UUID.randomUUID().toString()
        return claimCommitted(eventId, token).firstOrNull()?.let {
            Publication(it.sequence, it.eventId, token, it.expoId, it.snapshot, it.createdAt)
        }
    }

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(
        """UPDATE tb_expo_event_outbox SET state='PUBLISHED', published_at=CURRENT_TIMESTAMP,
            publish_token=NULL, publish_until=NULL WHERE sequence=:sequence AND publish_token=:token AND state='PENDING'""",
        nativeQuery = true,
    )
    fun markPublished(
        sequence: Long,
        token: String,
    ): Int

    @Transactional
    fun published(publication: Publication) {
        markPublished(publication.sequence, publication.token)
    }

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(
        """UPDATE tb_expo_event_outbox SET attempts=attempts+1,
            next_attempt_at=CURRENT_TIMESTAMP + make_interval(secs => LEAST(POWER(2, LEAST(attempts, 9)), 300)),
            publish_token=NULL, publish_until=NULL WHERE sequence=:sequence AND publish_token=:token AND state='PENDING'""",
        nativeQuery = true,
    )
    fun scheduleRetry(
        sequence: Long,
        token: String,
    ): Int

    @Transactional
    fun retry(publication: Publication) {
        scheduleRetry(publication.sequence, publication.token)
    }

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(
        "UPDATE tb_expo_event_outbox SET publish_token=NULL,publish_until=NULL WHERE state='PENDING' AND publish_until<=CURRENT_TIMESTAMP",
        nativeQuery = true,
    )
    fun releaseLeases(): Int

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(
        "DELETE FROM tb_expo_event_outbox WHERE state='PUBLISHED' AND published_at<=CURRENT_TIMESTAMP - INTERVAL '7 days'",
        nativeQuery = true,
    )
    fun deletePublished(): Int

    @Query(
        """SELECT count(*) AS count, min(sequence) AS oldestSequence FROM tb_expo_event_outbox
            WHERE state='PENDING' AND created_at<=CURRENT_TIMESTAMP - INTERVAL '10 minutes'""",
        nativeQuery = true,
    )
    fun findStuck(): StuckRow

    @Transactional
    fun maintain(): Stuck {
        releaseLeases()
        deletePublished()
        val row = findStuck()
        return Stuck(row.count, row.oldestSequence)
    }

    interface StuckRow {
        val count: Long
        val oldestSequence: Long?
    }

    data class Publication(
        val sequence: Long,
        val eventId: String,
        val token: String,
        val expoId: String,
        val snapshot: String?,
        val createdAt: Instant,
    )

    data class Stuck(
        val count: Long,
        val oldestSequence: Long?,
    )

    companion object {
        private const val LEASE = "'30 seconds'"
        private const val CLAIMABLE =
            "event.state='PENDING' AND event.next_attempt_at<=CURRENT_TIMESTAMP " +
                "AND (event.publish_until IS NULL OR event.publish_until<=CURRENT_TIMESTAMP) " +
                "AND NOT EXISTS (SELECT 1 FROM tb_expo_event_outbox earlier WHERE earlier.expo_id=event.expo_id " +
                "AND earlier.state='PENDING' AND earlier.sequence<event.sequence)"
    }
}
