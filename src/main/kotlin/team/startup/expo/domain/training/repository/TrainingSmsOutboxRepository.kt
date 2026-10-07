package team.startup.expo.domain.training.repository

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import org.springframework.transaction.support.TransactionTemplate
import java.util.UUID

@Repository
class TrainingSmsOutboxRepository(
    private val jdbc: JdbcTemplate,
    private val transactions: TransactionTemplate,
) {
    fun prepare(
        expoId: String,
        traineeId: Long,
        fingerprint: String,
        text: String,
    ): Operation? =
        transactions.execute {
            jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))", { _, _ -> Unit }, "$expoId:$traineeId")
            val latest =
                jdbc
                    .query(
                        """SELECT event_id, fingerprint, lease_until > CURRENT_TIMESTAMP AS busy FROM tb_training_sms_outbox
               WHERE expo_id = ? AND trainee_id = ? AND state <> 'REJECTED' ORDER BY sequence DESC LIMIT 1""",
                        { row, _ -> Triple(row.getString("event_id"), row.getString("fingerprint"), row.getBoolean("busy")) },
                        expoId,
                        traineeId,
                    ).firstOrNull()
            if (latest?.third == true) return@execute null
            val token = UUID.randomUUID().toString()
            val eventId = if (latest?.second == fingerprint) latest.first else UUID.randomUUID().toString()
            if (latest?.second == fingerprint) {
                jdbc.update(
                    """UPDATE tb_training_sms_outbox SET state = CASE WHEN state = 'PREPARED' THEN 'UNKNOWN' ELSE state END,
                   lease_token = ?, lease_until = CURRENT_TIMESTAMP + INTERVAL '30 seconds', updated_at = CURRENT_TIMESTAMP WHERE event_id = ?""",
                    token,
                    eventId,
                )
            } else {
                jdbc.update(
                    """INSERT INTO tb_training_sms_outbox(event_id, expo_id, trainee_id, fingerprint, state, notification_text, lease_token, lease_until)
                   VALUES (?, ?, ?, ?, 'PREPARED', ?, ?, CURRENT_TIMESTAMP + INTERVAL '30 seconds')""",
                    eventId,
                    expoId,
                    traineeId,
                    fingerprint,
                    text,
                    token,
                )
            }
            Operation(eventId, token)
        }

    fun confirmed(operation: Operation) {
        transactions.executeWithoutResult {
            jdbc.update(
                """UPDATE tb_training_sms_outbox SET state = CASE WHEN state IN ('PREPARED', 'UNKNOWN') THEN 'READY' ELSE state END,
                   lease_token = NULL, lease_until = NULL, updated_at = CURRENT_TIMESTAMP WHERE event_id = ? AND lease_token = ?""",
                operation.eventId,
                operation.token,
            )
        }
    }

    fun failed(
        operation: Operation,
        definiteRejection: Boolean,
    ) {
        transactions.executeWithoutResult {
            jdbc.update(
                """UPDATE tb_training_sms_outbox SET state = CASE WHEN state = 'PREPARED' THEN ? ELSE state END,
                   notification_text = CASE WHEN state = 'PREPARED' AND ? THEN NULL ELSE notification_text END,
                   lease_token = NULL, lease_until = NULL, updated_at = CURRENT_TIMESTAMP WHERE event_id = ? AND lease_token = ?""",
                if (definiteRejection) "REJECTED" else "UNKNOWN",
                definiteRejection,
                operation.eventId,
                operation.token,
            )
        }
    }

    fun claim(): Publication? =
        transactions.execute {
            val token = UUID.randomUUID().toString()
            jdbc
                .query(
                    """WITH candidate AS (
               SELECT sequence FROM tb_training_sms_outbox WHERE state = 'READY' AND next_attempt_at <= CURRENT_TIMESTAMP
               AND (publish_until IS NULL OR publish_until <= CURRENT_TIMESTAMP) ORDER BY sequence LIMIT 1 FOR UPDATE SKIP LOCKED)
               UPDATE tb_training_sms_outbox AS sms SET publish_token = ?, publish_until = CURRENT_TIMESTAMP + INTERVAL '30 seconds'
               FROM candidate WHERE sms.sequence = candidate.sequence
               RETURNING sms.event_id, sms.expo_id, sms.trainee_id, sms.notification_text, sms.payload""",
                    {
                        row,
                        _,
                        ->
                        Publication(
                            row.getString("event_id"),
                            token,
                            row.getString("expo_id"),
                            row.getLong("trainee_id"),
                            row.getString("notification_text"),
                            row.getString("payload"),
                        )
                    },
                    token,
                ).firstOrNull()
        }

    fun savePayload(
        publication: Publication,
        payload: String,
    ): Boolean =
        transactions.execute {
            jdbc.update(
                "UPDATE tb_training_sms_outbox SET payload = ? WHERE event_id = ? AND publish_token = ? AND state = 'READY'",
                payload,
                publication.eventId,
                publication.token,
            ) == 1
        } == true

    fun sent(publication: Publication) {
        transactions.executeWithoutResult {
            jdbc.update(
                """UPDATE tb_training_sms_outbox SET state = 'SENT', payload = NULL, notification_text = NULL,
                   publish_token = NULL, publish_until = NULL, updated_at = CURRENT_TIMESTAMP WHERE event_id = ? AND publish_token = ?""",
                publication.eventId,
                publication.token,
            )
        }
    }

    fun retry(publication: Publication) {
        transactions.executeWithoutResult {
            jdbc.update(
                """UPDATE tb_training_sms_outbox SET attempts = attempts + 1, updated_at = CURRENT_TIMESTAMP,
                   publish_token = NULL, publish_until = NULL, next_attempt_at = CURRENT_TIMESTAMP + INTERVAL '30 seconds'
                   WHERE event_id = ? AND publish_token = ?""",
                publication.eventId,
                publication.token,
            )
        }
    }

    data class Operation(
        val eventId: String,
        val token: String,
    )

    data class Publication(
        val eventId: String,
        val token: String,
        val expoId: String,
        val traineeId: Long,
        val text: String?,
        val payload: String?,
    )
}
