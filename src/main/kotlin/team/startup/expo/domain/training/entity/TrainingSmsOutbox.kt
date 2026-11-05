package team.startup.expo.domain.training.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.Immutable
import java.time.Instant

@Entity
@Immutable
@Table(name = "tb_training_sms_outbox")
class TrainingSmsOutbox(
    @field:Id
    @field:GeneratedValue(strategy = GenerationType.IDENTITY)
    @field:Column(name = "sequence", nullable = false)
    val sequence: Long,
    @field:Column(name = "event_id", length = 36, nullable = false)
    val eventId: String,
    @field:Column(name = "expo_id", length = 36, nullable = false)
    val expoId: String,
    @field:Column(name = "trainee_id", nullable = false)
    val traineeId: Long,
    @field:Column(name = "fingerprint", columnDefinition = "TEXT", nullable = false)
    val fingerprint: String,
    @field:Column(name = "state", length = 16, nullable = false)
    val state: String,
    @field:Column(name = "payload", columnDefinition = "TEXT")
    val payload: String?,
    @field:Column(name = "notification_text", columnDefinition = "TEXT")
    val notificationText: String?,
    @field:Column(name = "lease_token", length = 36)
    val leaseToken: String?,
    @field:Column(name = "lease_until")
    val leaseUntil: Instant?,
    @field:Column(name = "publish_token", length = 36)
    val publishToken: String?,
    @field:Column(name = "publish_until")
    val publishUntil: Instant?,
    @field:Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    val createdAt: Instant,
    @field:Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
    val updatedAt: Instant,
    @field:Column(name = "attempts", nullable = false, insertable = false, updatable = false)
    val attempts: Int,
    @field:Column(name = "next_attempt_at", nullable = false, insertable = false, updatable = false)
    val nextAttemptAt: Instant,
    @field:Column(name = "application_state", length = 16, nullable = false, insertable = false, updatable = false)
    val applicationState: String,
    @field:Column(name = "command_schema_version")
    val commandSchemaVersion: Int?,
    @field:Column(name = "operation_type", length = 8)
    val operationType: String?,
    @field:Column(name = "request_method", length = 4)
    val requestMethod: String?,
    @field:Column(name = "request_path", columnDefinition = "TEXT")
    val requestPath: String?,
    @field:Column(name = "command_json", columnDefinition = "TEXT")
    val commandJson: String?,
    @field:Column(name = "expected_version")
    val expectedVersion: Long?,
    @field:Column(name = "receipt_json", columnDefinition = "TEXT")
    val receiptJson: String?,
    @field:Column(name = "receipt_version")
    val receiptVersion: Long?,
    @field:Column(name = "response_received", nullable = false, insertable = false, updatable = false)
    val responseReceived: Boolean,
    @field:Column(name = "sms_requested", nullable = false, insertable = false, updatable = false)
    val smsRequested: Boolean,
    @field:Column(name = "first_publish_attempt_at")
    val firstPublishAttemptAt: Instant?,
    @field:Column(name = "terminal_at")
    val terminalAt: Instant?,
    @field:Column(name = "hold_reason", length = 40)
    val holdReason: String?,
    @field:Column(name = "recovery_attempts", nullable = false, insertable = false, updatable = false)
    val recoveryAttempts: Int,
    @field:Column(name = "recover_after", nullable = false, insertable = false, updatable = false)
    val recoverAfter: Instant,
)
