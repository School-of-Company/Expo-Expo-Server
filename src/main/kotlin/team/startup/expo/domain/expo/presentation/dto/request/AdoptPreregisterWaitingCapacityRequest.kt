package team.startup.expo.domain.expo.presentation.dto.request

import com.fasterxml.jackson.annotation.JsonProperty
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.PositiveOrZero

data class AdoptPreregisterWaitingCapacityRequest(
    @field:PositiveOrZero
    @param:JsonProperty(required = true)
    @field:Schema(description = "관리자가 확인한 Expo 회차의 현재 대기 정원. 0이면 대기 미사용으로 채택합니다.", example = "0")
    val expected: Int,
)
