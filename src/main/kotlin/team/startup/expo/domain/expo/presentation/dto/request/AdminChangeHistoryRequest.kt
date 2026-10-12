package team.startup.expo.domain.expo.presentation.dto.request

import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import java.time.Instant

enum class AdminChangeType { PROGRAM_CREATE, PROGRAM_UPDATE, PROGRAM_DELETE, SESSION_CREATE, SESSION_UPDATE, SESSION_DELETE }

data class AdminChangeHistoryRequest(
    @field:Min(0) val page: Int = 0,
    @field:Min(1) @field:Max(100) val size: Int = 20,
    val type: AdminChangeType? = null,
    val from: Instant? = null,
    val to: Instant? = null,
)
