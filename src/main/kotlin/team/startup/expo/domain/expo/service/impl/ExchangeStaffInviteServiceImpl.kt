package team.startup.expo.domain.expo.service.impl

import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import team.startup.expo.domain.expo.presentation.dto.request.ExchangeStaffInviteRequest
import team.startup.expo.domain.expo.presentation.dto.response.ExchangeStaffInviteResponse
import team.startup.expo.domain.expo.repository.ExpoRepository
import team.startup.expo.domain.expo.repository.ExpoStaffInviteRepository
import team.startup.expo.domain.expo.service.ExchangeStaffInviteService
import team.startup.expo.domain.expo.service.StaffInviteSupport
import team.startup.expo.global.exception.ExpectedException

@Service
class ExchangeStaffInviteServiceImpl(
    private val invites: ExpoStaffInviteRepository,
    private val expos: ExpoRepository,
    private val staff: StaffInviteSupport,
) : ExchangeStaffInviteService {
    @Transactional(readOnly = true)
    override fun execute(request: ExchangeStaffInviteRequest): ExchangeStaffInviteResponse {
        val invite =
            invites.findBySecretHash(staff.hash(request.secret))?.takeIf { it.isValid() }
                ?: throw ExpectedException(HttpStatus.FORBIDDEN, "유효한 스태프 링크가 아닙니다.")
        if (expos.existsByIdAndDeletingAtIsNotNull(invite.expoId)) {
            throw ExpectedException(HttpStatus.SERVICE_UNAVAILABLE, "삭제 중인 박람회입니다.")
        }
        return ExchangeStaffInviteResponse(invite.id, invite.expoId, invite.expiresAt)
    }
}
