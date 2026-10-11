package team.startup.expo.domain.expo.presentation.dto.response

import java.time.Instant
import java.util.UUID

data class ProgramFormUsageResponse(
    val formId: UUID,
    val referenced: Boolean,
    val applicationStartedAt: Instant?,
    val applicationEndedAt: Instant?,
)

data class ProgramFormChangeResponse(
    val formId: UUID,
    val referenced: Boolean,
    val applicationStartedAt: Instant?,
    val applicationEndedAt: Instant?,
    val changeId: UUID,
    val expiresAt: Instant,
)
