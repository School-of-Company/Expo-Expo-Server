package team.startup.expo.domain.training.repository

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import team.startup.expo.domain.training.entity.TrainingSmsOutbox
import team.startup.expo.domain.training.service.TrainingApplicationCommand
import team.startup.expo.domain.training.service.TrainingOperation
import java.time.Instant

interface TrainingSmsOutboxRepository : JpaRepository<TrainingSmsOutbox, Long> {
    @Query(
        """INSERT INTO tb_training_sms_outbox
                    (event_id,expo_id,trainee_id,fingerprint,state,notification_text,lease_token,lease_until,
                     application_state,command_schema_version,operation_type,request_method,request_path,command_json,expected_version,sms_requested,recover_after)
                    VALUES (:#{#command.operationId.toString()},:#{#command.trainee.expoId},:#{#command.trainee.id},:type,'$PREPARED',:text,:token,CURRENT_TIMESTAMP + INTERVAL '$LEASE',
                            '$PENDING',1,:type,:method,:path,:body,:#{#command.expectedVersion},:smsRequested,CURRENT_TIMESTAMP + INTERVAL '$LEASE') RETURNING *""",
        nativeQuery = true,
    )
    fun prepare(
        command: TrainingApplicationCommand,
        type: String,
        text: String?,
        token: String,
        method: String,
        path: String,
        body: String,
        smsRequested: Boolean,
    ): List<OperationRow>

    @Query(
        """SELECT EXISTS (SELECT 1 FROM tb_training_sms_outbox WHERE expo_id = :expoId
                AND (application_state IN ('$PENDING', '$HELD') OR hold_reason = 'LEGACY_UNCERTAIN'))""",
        nativeQuery = true,
    )
    fun hasUnresolvedApplications(expoId: String): Boolean

    @Modifying(flushAutomatically = true)
    @Query(
        """UPDATE tb_training_sms_outbox SET application_state = '$SUCCEEDED', receipt_json = :receipt, receipt_version = :version,
                   state = CASE WHEN sms_requested AND :notify THEN '$READY' ELSE '$SUPPRESSED' END,
                   notification_text = CASE WHEN sms_requested AND :notify THEN notification_text ELSE NULL END,
                   terminal_at = CASE WHEN sms_requested AND :notify THEN NULL ELSE CURRENT_TIMESTAMP END,
                   lease_token = NULL, lease_until = NULL, updated_at = CURRENT_TIMESTAMP
                   WHERE event_id = :#{#operation.eventId} AND lease_token = :#{#operation.token} AND application_state = '$PENDING'""",
        nativeQuery = true,
    )
    fun confirmed(
        operation: TrainingOperation,
        receipt: String,
        version: Long,
        notify: Boolean,
    ): Int

    @Modifying(flushAutomatically = true)
    @Query(
        """UPDATE tb_training_sms_outbox SET state = :state, application_state = :applicationState,
                   hold_reason = :reason, terminal_at = CASE WHEN :rejected THEN CURRENT_TIMESTAMP ELSE NULL END,
                   notification_text = CASE WHEN :rejected THEN NULL ELSE notification_text END,
                   payload = CASE WHEN :rejected THEN NULL ELSE payload END,
                   lease_token = NULL, lease_until = NULL, recover_after = CURRENT_TIMESTAMP + INTERVAL '$LEASE', updated_at = CURRENT_TIMESTAMP
                   WHERE event_id = :#{#operation.eventId} AND lease_token = :#{#operation.token} AND application_state = '$PENDING'""",
        nativeQuery = true,
    )
    fun pending(
        operation: TrainingOperation,
        state: String,
        applicationState: String,
        reason: String?,
        rejected: Boolean,
    ): Int

    @Query(
        """WITH candidate AS (
                    SELECT sequence FROM tb_training_sms_outbox WHERE application_state = '$PENDING'
                    AND recover_after <= CURRENT_TIMESTAMP AND (lease_until IS NULL OR lease_until <= CURRENT_TIMESTAMP)
                    ORDER BY recover_after, sequence LIMIT 1 FOR UPDATE SKIP LOCKED)
                    UPDATE tb_training_sms_outbox AS sms SET lease_token = :token, lease_until = CURRENT_TIMESTAMP + INTERVAL '$LEASE',
                    recovery_attempts = recovery_attempts + 1, recover_after = CURRENT_TIMESTAMP + INTERVAL '$LEASE'
                    FROM candidate WHERE sms.sequence = candidate.sequence RETURNING sms.*""",
        nativeQuery = true,
    )
    fun claimRecovery(token: String): List<OperationRow>

    @Query(
        """SELECT application_state = '$PENDING' AND lease_token = :#{#operation.token} AND lease_until > CURRENT_TIMESTAMP
                AND created_at > CURRENT_TIMESTAMP - INTERVAL '${REPLAY_WINDOW}' AND recovery_attempts <= ${RECOVERY_LIMIT} AND NOT response_received
                FROM tb_training_sms_outbox WHERE event_id = :#{#operation.eventId}""",
        nativeQuery = true,
    )
    fun canReplay(operation: TrainingOperation): Boolean?

    @Modifying(flushAutomatically = true)
    @Query(
        """UPDATE tb_training_sms_outbox SET application_state = '$HELD', state = '$HELD', hold_reason = :reason,
                    payload = NULL, notification_text = NULL, lease_token = NULL, lease_until = NULL, updated_at = CURRENT_TIMESTAMP
                    WHERE event_id = :#{#operation.eventId} AND lease_token = :#{#operation.token} AND application_state = '$PENDING'""",
        nativeQuery = true,
    )
    fun hold(
        operation: TrainingOperation,
        reason: String,
    ): Int

    @Query(
        """WITH candidate AS (
               SELECT sequence FROM tb_training_sms_outbox WHERE state = '$READY' AND application_state = '$SUCCEEDED'
               AND created_at > CURRENT_TIMESTAMP - INTERVAL '${SMS_WINDOW}' AND next_attempt_at <= CURRENT_TIMESTAMP
               AND (first_publish_attempt_at IS NULL OR first_publish_attempt_at > CURRENT_TIMESTAMP - INTERVAL '${SMS_WINDOW}')
               AND (publish_until IS NULL OR publish_until <= CURRENT_TIMESTAMP) ORDER BY sequence LIMIT 1 FOR UPDATE SKIP LOCKED)
               UPDATE tb_training_sms_outbox AS sms SET publish_token = :token, publish_until = CURRENT_TIMESTAMP + INTERVAL '$LEASE'
               FROM candidate WHERE sms.sequence = candidate.sequence
               RETURNING sms.*""",
        nativeQuery = true,
    )
    fun claim(token: String): List<PublicationRow>

    @Modifying(flushAutomatically = true)
    @Query(
        """UPDATE tb_training_sms_outbox SET payload = :payload WHERE event_id = :#{#publication.eventId} AND publish_token = :#{#publication.token} AND state = '$READY'
                    AND first_publish_attempt_at IS NULL AND created_at > CURRENT_TIMESTAMP - INTERVAL '${SMS_WINDOW}'""",
        nativeQuery = true,
    )
    fun savePayload(
        publication: Publication,
        payload: String,
    ): Int

    @Modifying(flushAutomatically = true)
    @Query(
        """UPDATE tb_training_sms_outbox SET first_publish_attempt_at = COALESCE(first_publish_attempt_at, CURRENT_TIMESTAMP)
                    WHERE event_id = :#{#publication.eventId} AND publish_token = :#{#publication.token} AND publish_until > CURRENT_TIMESTAMP AND state = '$READY'
                    AND payload IS NOT NULL AND created_at > CURRENT_TIMESTAMP - INTERVAL '${SMS_WINDOW}'
                    AND (first_publish_attempt_at IS NULL OR first_publish_attempt_at > CURRENT_TIMESTAMP - INTERVAL '${SMS_WINDOW}')""",
        nativeQuery = true,
    )
    fun beginPublication(publication: Publication): Int

    @Modifying(flushAutomatically = true)
    @Query(
        """UPDATE tb_training_sms_outbox SET state = '$HELD', hold_reason = :reason, payload = NULL, notification_text = NULL,
                    terminal_at = CURRENT_TIMESTAMP, publish_token = NULL, publish_until = NULL, updated_at = CURRENT_TIMESTAMP
                    WHERE event_id = :#{#publication.eventId} AND publish_token = :#{#publication.token} AND state = '$READY' AND first_publish_attempt_at IS NULL""",
        nativeQuery = true,
    )
    fun holdPublication(
        publication: Publication,
        reason: String,
    ): Int

    @Modifying(flushAutomatically = true)
    @Query(
        """UPDATE tb_training_sms_outbox SET application_state = '$HELD', state = '$HELD', hold_reason = :reason,
                    notification_text = NULL, payload = NULL, lease_token = NULL, lease_until = NULL, updated_at = CURRENT_TIMESTAMP
                    WHERE application_state = '$PENDING' AND (created_at <= CURRENT_TIMESTAMP - INTERVAL '${REPLAY_WINDOW}' OR recovery_attempts >= ${RECOVERY_LIMIT})
                    AND (lease_until IS NULL OR lease_until <= CURRENT_TIMESTAMP)""",
        nativeQuery = true,
    )
    fun holdRecoveryLimit(reason: String): Int

    @Modifying(flushAutomatically = true)
    @Query(
        """UPDATE tb_training_sms_outbox SET state = '$HELD', hold_reason = :reason,
                    terminal_at = CASE WHEN application_state = '$SUCCEEDED' THEN CURRENT_TIMESTAMP ELSE terminal_at END,
                    notification_text = NULL, payload = NULL, publish_token = NULL, publish_until = NULL, updated_at = CURRENT_TIMESTAMP
                    WHERE created_at <= CURRENT_TIMESTAMP - INTERVAL '${SMS_WINDOW}' AND (notification_text IS NOT NULL OR payload IS NOT NULL)
                    AND (publish_until IS NULL OR publish_until <= CURRENT_TIMESTAMP)""",
        nativeQuery = true,
    )
    fun expireSms(reason: String): Int

    @Modifying(flushAutomatically = true)
    @Query(
        """UPDATE tb_training_sms_outbox SET state = '$SENT', payload = NULL, notification_text = NULL,
                   terminal_at = CURRENT_TIMESTAMP, publish_token = NULL, publish_until = NULL, updated_at = CURRENT_TIMESTAMP
                   WHERE event_id = :#{#publication.eventId} AND publish_token = :#{#publication.token} AND state = '$READY'""",
        nativeQuery = true,
    )
    fun sent(publication: Publication): Int

    @Modifying(flushAutomatically = true)
    @Query(
        """UPDATE tb_training_sms_outbox SET attempts = attempts + 1, updated_at = CURRENT_TIMESTAMP,
                   publish_token = NULL, publish_until = NULL, next_attempt_at = CURRENT_TIMESTAMP + INTERVAL '$LEASE'
                   WHERE event_id = :#{#publication.eventId} AND publish_token = :#{#publication.token} AND state = '$READY'""",
        nativeQuery = true,
    )
    fun retry(publication: Publication): Int

    @Modifying(flushAutomatically = true)
    @Query(
        """UPDATE tb_training_sms_outbox SET response_received=TRUE
            WHERE event_id=:#{#operation.eventId} AND lease_token=:#{#operation.token} AND application_state='$PENDING'""",
        nativeQuery = true,
    )
    fun responseReceived(operation: TrainingOperation): Int

    @Modifying(flushAutomatically = true)
    @Query(
        """DELETE FROM tb_training_sms_outbox WHERE terminal_at<=CURRENT_TIMESTAMP - INTERVAL '30 days'
            AND application_state IN ('$SUCCEEDED','$REJECTED','$LEGACY')""",
        nativeQuery = true,
    )
    fun deleteTerminal(): Int

    interface OperationRow {
        val eventId: String
        val leaseToken: String
        val operationType: String
        val expoId: String
        val traineeId: Long
        val requestMethod: String
        val requestPath: String
        val commandJson: String
        val expectedVersion: Long
        val createdAt: Instant
        val responseReceived: Boolean
    }

    interface PublicationRow {
        val eventId: String
        val publishToken: String
        val expoId: String
        val traineeId: Long
        val notificationText: String?
        val payload: String?
        val receiptVersion: Long?
        val firstPublishAttemptAt: Instant?
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

    companion object {
        private const val LEASE = "30 seconds"
        private const val SMS_WINDOW = "23 hours"
        private const val REPLAY_WINDOW = "15 minutes"
        private const val RECOVERY_LIMIT = 30
        private const val PREPARED = "PREPARED"
        private const val REJECTED = "REJECTED"
        private const val READY = "READY"
        private const val SENT = "SENT"
        private const val SUPPRESSED = "SUPPRESSED"
        private const val HELD = "HELD"
        private const val LEGACY = "LEGACY"
        private const val PENDING = "PENDING"
        private const val SUCCEEDED = "SUCCEEDED"
    }
}
