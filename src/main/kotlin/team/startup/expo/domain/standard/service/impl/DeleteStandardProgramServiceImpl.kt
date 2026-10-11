package team.startup.expo.domain.standard.service.impl

import org.springframework.data.repository.findByIdOrNull
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import team.startup.expo.domain.expo.presentation.dto.response.ProgramChangePayload
import team.startup.expo.domain.expo.presentation.dto.response.ProgramMutation
import team.startup.expo.domain.expo.service.ApplyProgramChangeService
import team.startup.expo.domain.expo.service.ProgramPolicySupport
import team.startup.expo.domain.standard.repository.StandardProgramRepository
import team.startup.expo.domain.standard.service.DeleteStandardProgramService
import team.startup.expo.global.exception.ExpectedException

@Service
class DeleteStandardProgramServiceImpl(
    private val programs: StandardProgramRepository,
    private val policies: ProgramPolicySupport,
    private val applyChange: ApplyProgramChangeService,
) : DeleteStandardProgramService {
    override fun execute(programId: Long) {
        val expoId = programs.findByIdOrNull(programId)?.expo?.id ?: missing()
        val pending =
            policies.stage(expoId, policies.command("DELETE_STANDARD", programId)) {
                val current = programs.findByIdOrNull(programId) ?: missing()
                ProgramChangePayload(listOf(ProgramMutation(policies.definition(current), null)))
            } ?: return
        applyChange.execute(expoId, pending.changeId)
    }

    private fun missing(): Nothing = throw ExpectedException(HttpStatus.NOT_FOUND, "일반 프로그램을 찾지 못 했습니다.")
}
