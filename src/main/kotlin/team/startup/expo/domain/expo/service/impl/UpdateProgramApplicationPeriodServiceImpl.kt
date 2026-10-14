package team.startup.expo.domain.expo.service.impl

import org.springframework.stereotype.Service
import team.startup.expo.domain.expo.presentation.dto.request.UpdateProgramApplicationPeriodRequest
import team.startup.expo.domain.expo.presentation.dto.response.ProgramPolicyBulkResponse
import team.startup.expo.domain.expo.service.ApplyProgramChangeService
import team.startup.expo.domain.expo.service.ProgramPolicySupport
import team.startup.expo.domain.expo.service.UpdateProgramApplicationPeriodService

@Service
class UpdateProgramApplicationPeriodServiceImpl(
    private val policies: ProgramPolicySupport,
    private val applyChange: ApplyProgramChangeService,
) : UpdateProgramApplicationPeriodService {
    override fun execute(
        expoId: String,
        request: UpdateProgramApplicationPeriodRequest,
    ): ProgramPolicyBulkResponse {
        request.validate()
        val (pending, results) =
            policies.stageBulk(expoId, policies.command("UPDATE_APPLICATION_PERIOD", expoId, request)) {
                it.copy(applicationStartedAt = request.applicationStartedAt, applicationEndedAt = request.applicationEndedAt)
            }
        pending?.let { applyChange.execute(expoId, it.changeId) }
        return ProgramPolicyBulkResponse(results)
    }
}
