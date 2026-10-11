package team.startup.expo.domain.expo.presentation.dto.request

import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.Pattern
import team.startup.expo.domain.expo.presentation.dto.response.ProgramType
import java.util.UUID

data class ProgramSessionPageRequest(
    val programType: ProgramType? = null,
    val groupId: UUID? = null,
    @field:Min(1) val programId: Long? = null,
    @field:Min(0) val page: Int = 0,
    @field:Min(1) @field:Max(100) val size: Int = 100,
    @field:Pattern(regexp = "[a-f0-9]{32}") val definitionVersion: String? = null,
)
