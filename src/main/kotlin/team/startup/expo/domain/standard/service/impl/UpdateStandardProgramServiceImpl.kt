package team.startup.expo.domain.standard.service.impl

import org.springframework.data.repository.findByIdOrNull
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import team.startup.expo.domain.expo.presentation.dto.response.ProgramChangePayload
import team.startup.expo.domain.expo.service.ApplyProgramChangeService
import team.startup.expo.domain.expo.service.ProgramPolicySupport
import team.startup.expo.domain.standard.presentation.dto.request.UpdateStandardProgramRequest
import team.startup.expo.domain.standard.repository.StandardProgramRepository
import team.startup.expo.domain.standard.service.UpdateStandardProgramService
import team.startup.expo.global.exception.ExpectedException

@Service
class UpdateStandardProgramServiceImpl(
    private val programs: StandardProgramRepository,
    private val policies: ProgramPolicySupport,
    private val applyChange: ApplyProgramChangeService,
) : UpdateStandardProgramService {
    override fun execute(
        programId: Long,
        request: UpdateStandardProgramRequest,
    ) {
        val expoId = programs.findByIdOrNull(programId)?.expo?.id ?: missing()
        val pending =
            policies.stage(expoId, policies.command("UPDATE_STANDARD", programId, request)) {
                val current = programs.findByIdOrNull(programId) ?: missing()
                val mutation =
                    policies.update(
                        policies.definition(current),
                        request.title,
                        request.startedAt.toString(),
                        request.endedAt.toString(),
                        null,
                        request.policy,
                        request.revision,
                        place = request.place,
                    )
                ProgramChangePayload(listOfNotNull(mutation))
            } ?: return
        applyChange.execute(expoId, pending.changeId)
    }

    private fun missing(): Nothing = throw ExpectedException(HttpStatus.NOT_FOUND, "일반 프로그램을 찾지 못 했습니다.")
}
