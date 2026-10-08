package team.startup.expo.domain.training.repository

import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import org.springframework.transaction.support.TransactionTemplate
import team.startup.expo.domain.training.service.TrainingApplicationCommand
import team.startup.expo.domain.training.service.TrainingOperation
import team.startup.expo.domain.training.service.TrainingOperationReceipt
import team.startup.expo.domain.training.service.TrainingOperationType
import team.startup.expo.global.exception.ExpectedException
import tools.jackson.databind.ObjectMapper
import java.sql.ResultSet
import java.time.Instant
import java.util.UUID

@Repository
class TrainingSmsOutboxRepository(
    private val jdbc: JdbcTemplate,
    private val transactions: TransactionTemplate,
    private val mapper: ObjectMapper,
) {
    fun prepare(
        command: TrainingApplicationCommand,
        type: TrainingOperationType,
        text: String?,
    ): TrainingOperation =
        requireNotNull(
            transactions.execute {
                val deleting =
                    jdbc
                        .query(
                            "SELECT deleting_at IS NOT NULL FROM tb_expo WHERE id = ? FOR UPDATE",
                            { row, _ -> row.getBoolean(1) },
                            command.trainee.expoId,
                        ).firstOrNull() ?: throw ExpectedException(HttpStatus.NOT_FOUND, "박람회를 찾지 못 했습니다.")
                if (deleting) throw ExpectedException(HttpStatus.CONFLICT, "삭제 중인 박람회입니다.")
                val eventId = command.operationId.toString()
                val token = UUID.randomUUID().toString()
                val method = if (type == TrainingOperationType.ADD) "POST" else "PUT"
                val path =
                    "/internal/training-program-applications" +
                        if (type == TrainingOperationType.REPLACE) "/trainee/${command.trainee.id}" else ""
                val body = mapper.writeValueAsString(command)
                jdbc.queryForObject(
                    """INSERT INTO tb_training_sms_outbox
                    (event_id,expo_id,trainee_id,fingerprint,state,notification_text,lease_token,lease_until,
                     application_state,command_schema_version,operation_type,request_method,request_path,command_json,expected_version,sms_requested,recover_after)
                    VALUES (?,?,?,?,'PREPARED',?,?,CURRENT_TIMESTAMP + INTERVAL '30 seconds',
                            'PENDING',1,?,?,?,?,?,?,CURRENT_TIMESTAMP + INTERVAL '30 seconds') RETURNING *""",
                    { row, _ -> operation(row) },
                    eventId,
                    command.trainee.expoId,
                    command.trainee.id,
                    type.name,
                    text,
                    token,
                    type.name,
                    method,
                    path,
                    body,
                    command.expectedVersion,
                    text != null,
                )
            },
        )

    fun hasUnresolvedApplications(expoId: String): Boolean =
        jdbc.queryForObject(
            """SELECT EXISTS (SELECT 1 FROM tb_training_sms_outbox WHERE expo_id = ?
                AND (application_state IN ('PENDING', 'HELD') OR hold_reason = 'LEGACY_UNCERTAIN'))""",
            Boolean::class.java,
            expoId,
        ) == true

    fun confirmed(
        operation: TrainingOperation,
        receipt: TrainingOperationReceipt,
    ) {
        transactions.executeWithoutResult {
            jdbc.update(
                """UPDATE tb_training_sms_outbox SET application_state = 'SUCCEEDED', receipt_json = ?, receipt_version = ?,
                   state = CASE WHEN sms_requested AND ? THEN 'READY' ELSE 'SUPPRESSED' END,
                   notification_text = CASE WHEN sms_requested AND ? THEN notification_text ELSE NULL END,
                   terminal_at = CASE WHEN sms_requested AND ? THEN NULL ELSE CURRENT_TIMESTAMP END,
                   lease_token = NULL, lease_until = NULL, updated_at = CURRENT_TIMESTAMP
                   WHERE event_id = ? AND lease_token = ? AND application_state = 'PENDING'""",
                mapper.writeValueAsString(receipt),
                receipt.version,
                receipt.changed && receipt.programIds.isNotEmpty(),
                receipt.changed && receipt.programIds.isNotEmpty(),
                receipt.changed && receipt.programIds.isNotEmpty(),
                operation.eventId,
                operation.token,
            )
        }
    }

    fun responseReceived(operation: TrainingOperation) {
        transactions.executeWithoutResult {
            jdbc.update(
                "UPDATE tb_training_sms_outbox SET response_received = TRUE WHERE event_id = ? AND lease_token = ? AND application_state = 'PENDING'",
                operation.eventId,
                operation.token,
            )
        }
    }

    fun pending(
        operation: TrainingOperation,
        rejected: Boolean = false,
    ) {
        transactions.executeWithoutResult {
            jdbc.update(
                """UPDATE tb_training_sms_outbox SET state = ?, application_state = ?,
                   hold_reason = ?, terminal_at = CASE WHEN ? THEN CURRENT_TIMESTAMP ELSE NULL END,
                   notification_text = CASE WHEN ? THEN NULL ELSE notification_text END,
                   payload = CASE WHEN ? THEN NULL ELSE payload END,
                   lease_token = NULL, lease_until = NULL, recover_after = CURRENT_TIMESTAMP + INTERVAL '30 seconds', updated_at = CURRENT_TIMESTAMP
                   WHERE event_id = ? AND lease_token = ? AND application_state = 'PENDING'""",
                if (rejected) "REJECTED" else "UNKNOWN",
                if (rejected) "REJECTED" else "PENDING",
                if (rejected) "APPLICATION_REJECTED" else null,
                rejected,
                rejected,
                rejected,
                operation.eventId,
                operation.token,
            )
        }
    }

    fun claimRecovery(): TrainingOperation? =
        transactions.execute {
            val token = UUID.randomUUID().toString()
            jdbc
                .query(
                    """WITH candidate AS (
                    SELECT sequence FROM tb_training_sms_outbox WHERE application_state = 'PENDING'
                    AND recover_after <= CURRENT_TIMESTAMP AND (lease_until IS NULL OR lease_until <= CURRENT_TIMESTAMP)
                    ORDER BY recover_after, sequence LIMIT 1 FOR UPDATE SKIP LOCKED)
                    UPDATE tb_training_sms_outbox AS sms SET lease_token = ?, lease_until = CURRENT_TIMESTAMP + INTERVAL '30 seconds',
                    recovery_attempts = recovery_attempts + 1, recover_after = CURRENT_TIMESTAMP + INTERVAL '30 seconds'
                    FROM candidate WHERE sms.sequence = candidate.sequence RETURNING sms.*""",
                    { row, _ -> operation(row) },
                    token,
                ).firstOrNull()
        }

    fun canReplay(operation: TrainingOperation): Boolean =
        jdbc.queryForObject(
            """SELECT application_state = 'PENDING' AND lease_token = ? AND lease_until > CURRENT_TIMESTAMP
                AND created_at > CURRENT_TIMESTAMP - INTERVAL '15 minutes' AND recovery_attempts <= 30 AND NOT response_received
                FROM tb_training_sms_outbox WHERE event_id = ?""",
            Boolean::class.java,
            operation.token,
            operation.eventId,
        ) == true

    fun hold(
        operation: TrainingOperation,
        reason: String,
    ) {
        transactions.executeWithoutResult {
            jdbc.update(
                """UPDATE tb_training_sms_outbox SET application_state = 'HELD', state = 'HELD', hold_reason = ?,
                    payload = NULL, notification_text = NULL, lease_token = NULL, lease_until = NULL, updated_at = CURRENT_TIMESTAMP
                    WHERE event_id = ? AND lease_token = ? AND application_state = 'PENDING'""",
                reason,
                operation.eventId,
                operation.token,
            )
        }
    }

    private fun operation(row: ResultSet) =
        TrainingOperation(
            row.getString("event_id"),
            row.getString("lease_token"),
            TrainingOperationType.valueOf(row.getString("operation_type")),
            row.getString("expo_id"),
            row.getLong("trainee_id"),
            row.getString("request_method"),
            row.getString("request_path"),
            row.getString("command_json"),
            row.getLong("expected_version"),
            row.getTimestamp("created_at").toInstant(),
            row.getBoolean("response_received"),
        )

    fun claim(): Publication? =
        transactions.execute {
            val token = UUID.randomUUID().toString()
            jdbc
                .query(
                    """WITH candidate AS (
               SELECT sequence FROM tb_training_sms_outbox WHERE state = 'READY' AND application_state = 'SUCCEEDED'
               AND created_at > CURRENT_TIMESTAMP - INTERVAL '23 hours' AND next_attempt_at <= CURRENT_TIMESTAMP
               AND (first_publish_attempt_at IS NULL OR first_publish_attempt_at > CURRENT_TIMESTAMP - INTERVAL '23 hours')
               AND (publish_until IS NULL OR publish_until <= CURRENT_TIMESTAMP) ORDER BY sequence LIMIT 1 FOR UPDATE SKIP LOCKED)
               UPDATE tb_training_sms_outbox AS sms SET publish_token = ?, publish_until = CURRENT_TIMESTAMP + INTERVAL '30 seconds'
               FROM candidate WHERE sms.sequence = candidate.sequence
               RETURNING sms.event_id, sms.expo_id, sms.trainee_id, sms.notification_text, sms.payload, sms.receipt_version, sms.first_publish_attempt_at""",
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
                            row.getLong("receipt_version"),
                            row.getTimestamp("first_publish_attempt_at")?.toInstant(),
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
                """UPDATE tb_training_sms_outbox SET payload = ? WHERE event_id = ? AND publish_token = ? AND state = 'READY'
                    AND first_publish_attempt_at IS NULL AND created_at > CURRENT_TIMESTAMP - INTERVAL '23 hours'""",
                payload,
                publication.eventId,
                publication.token,
            ) == 1
        } == true

    fun beginPublication(publication: Publication): Boolean =
        transactions.execute {
            jdbc.update(
                """UPDATE tb_training_sms_outbox SET first_publish_attempt_at = COALESCE(first_publish_attempt_at, CURRENT_TIMESTAMP)
                    WHERE event_id = ? AND publish_token = ? AND publish_until > CURRENT_TIMESTAMP AND state = 'READY'
                    AND payload IS NOT NULL AND created_at > CURRENT_TIMESTAMP - INTERVAL '23 hours'
                    AND (first_publish_attempt_at IS NULL OR first_publish_attempt_at > CURRENT_TIMESTAMP - INTERVAL '23 hours')""",
                publication.eventId,
                publication.token,
            ) == 1
        } == true

    fun holdPublication(
        publication: Publication,
        reason: String,
    ) {
        transactions.executeWithoutResult {
            jdbc.update(
                """UPDATE tb_training_sms_outbox SET state = 'HELD', hold_reason = ?, payload = NULL, notification_text = NULL,
                    terminal_at = CURRENT_TIMESTAMP, publish_token = NULL, publish_until = NULL, updated_at = CURRENT_TIMESTAMP
                    WHERE event_id = ? AND publish_token = ? AND state = 'READY' AND first_publish_attempt_at IS NULL""",
                reason,
                publication.eventId,
                publication.token,
            )
        }
    }

    fun maintain() {
        transactions.executeWithoutResult {
            jdbc.update(
                """UPDATE tb_training_sms_outbox SET application_state = 'HELD', state = 'HELD', hold_reason = 'RECOVERY_LIMIT',
                    notification_text = NULL, payload = NULL, lease_token = NULL, lease_until = NULL, updated_at = CURRENT_TIMESTAMP
                    WHERE application_state = 'PENDING' AND (created_at <= CURRENT_TIMESTAMP - INTERVAL '15 minutes' OR recovery_attempts >= 30)
                    AND (lease_until IS NULL OR lease_until <= CURRENT_TIMESTAMP)""",
            )
            jdbc.update(
                """UPDATE tb_training_sms_outbox SET state = 'HELD', hold_reason = 'SMS_EXPIRED',
                    terminal_at = CASE WHEN application_state = 'SUCCEEDED' THEN CURRENT_TIMESTAMP ELSE terminal_at END,
                    notification_text = NULL, payload = NULL, publish_token = NULL, publish_until = NULL, updated_at = CURRENT_TIMESTAMP
                    WHERE created_at <= CURRENT_TIMESTAMP - INTERVAL '23 hours' AND (notification_text IS NOT NULL OR payload IS NOT NULL)
                    AND (publish_until IS NULL OR publish_until <= CURRENT_TIMESTAMP)""",
            )
            jdbc.update(
                "DELETE FROM tb_training_sms_outbox WHERE terminal_at <= CURRENT_TIMESTAMP - INTERVAL '30 days' AND application_state IN ('SUCCEEDED', 'REJECTED', 'LEGACY')",
            )
        }
    }

    fun sent(publication: Publication) {
        transactions.executeWithoutResult {
            jdbc.update(
                """UPDATE tb_training_sms_outbox SET state = 'SENT', payload = NULL, notification_text = NULL,
                   terminal_at = CURRENT_TIMESTAMP, publish_token = NULL, publish_until = NULL, updated_at = CURRENT_TIMESTAMP
                   WHERE event_id = ? AND publish_token = ? AND state = 'READY'""",
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
                   WHERE event_id = ? AND publish_token = ? AND state = 'READY'""",
                publication.eventId,
                publication.token,
            )
        }
    }

    data class Publication(
        val eventId: String,
        val token: String,
        val expoId: String,
        val traineeId: Long,
        val text: String?,
        val payload: String?,
        val receiptVersion: Long,
        val firstPublishAttemptAt: Instant?,
    )
}
