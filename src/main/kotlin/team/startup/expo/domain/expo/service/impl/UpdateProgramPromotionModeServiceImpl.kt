package team.startup.expo.domain.expo.service.impl

import org.springframework.stereotype.Service
import team.startup.expo.domain.expo.presentation.dto.request.UpdateProgramPromotionModeRequest
import team.startup.expo.domain.expo.presentation.dto.response.ProgramPolicyBulkResponse
import team.startup.expo.domain.expo.service.ApplyProgramChangeService
import team.startup.expo.domain.expo.service.ProgramPolicySupport
import team.startup.expo.domain.expo.service.UpdateProgramPromotionModeService

@Service
class UpdateProgramPromotionModeServiceImpl(
    private val policies: ProgramPolicySupport,
    private val applyChange: ApplyProgramChangeService,
) : UpdateProgramPromotionModeService {
    override fun execute(
        expoId: String,
        request: UpdateProgramPromotionModeRequest,
    ): ProgramPolicyBulkResponse {
        request.validate()
        val (pending, results) =
            policies.stageBulk(expoId, policies.command("UPDATE_PROMOTION_MODE", expoId, request)) {
                it.copy(promotionMode = request.promotionMode, promotionEndedAt = request.promotionEndedAt)
            }
        pending?.let { applyChange.execute(expoId, it.changeId) }
        return ProgramPolicyBulkResponse(results)
    }
}
