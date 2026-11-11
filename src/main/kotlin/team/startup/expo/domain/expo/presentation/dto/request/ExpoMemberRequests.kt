package team.startup.expo.domain.expo.presentation.dto.request

import jakarta.validation.constraints.Future
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Positive
import jakarta.validation.constraints.Size
import team.startup.expo.domain.expo.entity.ExpoMemberRole
import java.time.Instant

data class CreateExpoMemberRequest(
    @field:Positive val adminId: Long,
    val role: ExpoMemberRole,
)

data class UpdateExpoMemberRequest(
    val role: ExpoMemberRole,
)

data class CreateExpoStaffInviteRequest(
    @field:NotBlank @field:Size(max = 100) val label: String,
    @field:Future val expiresAt: Instant,
)

data class ExchangeStaffInviteRequest(
    @field:NotBlank @field:Size(max = 128) val secret: String,
)
