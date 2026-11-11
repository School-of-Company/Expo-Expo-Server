package team.startup.expo.domain.expo.presentation

import io.swagger.v3.oas.annotations.Operation
import jakarta.validation.Valid
import org.springframework.http.CacheControl
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import team.startup.expo.domain.expo.presentation.dto.request.ExchangeStaffInviteRequest
import team.startup.expo.domain.expo.presentation.dto.response.ExchangeStaffInviteResponse
import team.startup.expo.domain.expo.service.ExchangeStaffInviteService

@RestController
class InternalStaffInviteController(
    private val exchange: ExchangeStaffInviteService,
) {
    @Operation(summary = "User 서비스의 스태프 링크 교환")
    @PostMapping("/internal/staff-invites/exchange")
    fun exchange(
        @Valid @RequestBody request: ExchangeStaffInviteRequest,
    ): ResponseEntity<ExchangeStaffInviteResponse> =
        ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(exchange.execute(request))
}
