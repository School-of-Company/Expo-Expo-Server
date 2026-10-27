package team.startup.expo.domain.expo.repository

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant
import java.util.UUID

@Repository
class ExpoEventOutboxRepository(
    private val jdbc: JdbcTemplate,
    private val transactions: TransactionTemplate,
) {
    // 호출한 쪽의 트랜잭션에서 쓴다. snapshot이 null이면 삭제(tombstone)다.
    fun insert(
        expoId: String,
        snapshot: String?,
    ) {
        jdbc.update(
            "INSERT INTO tb_expo_event_outbox (event_id, expo_id, deleted, snapshot) VALUES (?, ?, ?, ?)",
            UUID.randomUUID().toString(),
            expoId,
            snapshot == null,
            snapshot,
        )
    }

    // 박람회마다 가장 앞선 대기 행만 선점해 한 박람회의 이벤트가 sequence 순서대로 나가게 한다.
    fun claim(): Publication? =
        transactions.execute {
            val token = UUID.randomUUID().toString()
            jdbc
                .query(
                    """WITH candidate AS (
                    SELECT event.sequence FROM tb_expo_event_outbox AS event
                    WHERE event.state = 'PENDING' AND event.next_attempt_at <= CURRENT_TIMESTAMP
                    AND (event.publish_until IS NULL OR event.publish_until <= CURRENT_TIMESTAMP)
                    AND NOT EXISTS (SELECT 1 FROM tb_expo_event_outbox AS earlier
                        WHERE earlier.expo_id = event.expo_id AND earlier.state = 'PENDING' AND earlier.sequence < event.sequence)
                    ORDER BY event.sequence LIMIT 1 FOR UPDATE SKIP LOCKED)
                    UPDATE tb_expo_event_outbox AS event SET publish_token = ?, publish_until = CURRENT_TIMESTAMP + INTERVAL '30 seconds'
                    FROM candidate WHERE event.sequence = candidate.sequence
                    RETURNING event.sequence, event.event_id, event.expo_id, event.snapshot, event.created_at""",
                    { row, _ ->
                        Publication(
                            row.getLong("sequence"),
                            row.getString("event_id"),
                            token,
                            row.getString("expo_id"),
                            row.getString("snapshot"),
                            row.getTimestamp("created_at").toInstant(),
                        )
                    },
                    token,
                ).firstOrNull()
        }

    fun published(publication: Publication) {
        transactions.executeWithoutResult {
            jdbc.update(
                """UPDATE tb_expo_event_outbox SET state = 'PUBLISHED', published_at = CURRENT_TIMESTAMP,
                   publish_token = NULL, publish_until = NULL WHERE sequence = ? AND publish_token = ? AND state = 'PENDING'""",
                publication.sequence,
                publication.token,
            )
        }
    }

    // 1초부터 두 배씩 늘려 최대 5분 뒤에 다시 시도한다.
    fun retry(publication: Publication) {
        transactions.executeWithoutResult {
            jdbc.update(
                """UPDATE tb_expo_event_outbox SET attempts = attempts + 1,
                   next_attempt_at = CURRENT_TIMESTAMP + make_interval(secs => LEAST(POWER(2, LEAST(attempts, 9)), 300)),
                   publish_token = NULL, publish_until = NULL WHERE sequence = ? AND publish_token = ? AND state = 'PENDING'""",
                publication.sequence,
                publication.token,
            )
        }
    }

    // 선점한 채 멈춘 행의 lease를 풀고, 발행한 지 7일 지난 행을 지운 뒤 10분 넘게 남은 대기 행 수를 돌려준다.
    fun maintain(): Stuck =
        requireNotNull(
            transactions.execute {
                jdbc.update(
                    """UPDATE tb_expo_event_outbox SET publish_token = NULL, publish_until = NULL
                       WHERE state = 'PENDING' AND publish_until <= CURRENT_TIMESTAMP""",
                )
                jdbc.update(
                    "DELETE FROM tb_expo_event_outbox WHERE state = 'PUBLISHED' AND published_at <= CURRENT_TIMESTAMP - INTERVAL '7 days'",
                )
                jdbc.queryForObject(
                    """SELECT count(*), min(sequence) FROM tb_expo_event_outbox
                       WHERE state = 'PENDING' AND created_at <= CURRENT_TIMESTAMP - INTERVAL '10 minutes'""",
                ) { row, _ -> Stuck(row.getLong(1), row.getObject(2) as Long?) }
            },
        )

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
}
