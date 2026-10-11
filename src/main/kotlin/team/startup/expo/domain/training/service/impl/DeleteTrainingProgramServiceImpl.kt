package team.startup.expo.domain.training.service.impl

import org.springframework.data.repository.findByIdOrNull
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import team.startup.expo.domain.expo.presentation.dto.response.ProgramChangePayload
import team.startup.expo.domain.expo.presentation.dto.response.ProgramMutation
import team.startup.expo.domain.expo.service.ApplyProgramChangeService
import team.startup.expo.domain.expo.service.ProgramPolicySupport
import team.startup.expo.domain.training.repository.TrainingProgramRepository
import team.startup.expo.domain.training.service.DeleteTrainingProgramService
import team.startup.expo.global.exception.ExpectedException

@Service
class DeleteTrainingProgramServiceImpl(
    private val programs: TrainingProgramRepository,
    private val policies: ProgramPolicySupport,
    private val applyChange: ApplyProgramChangeService,
) : DeleteTrainingProgramService {
    override fun execute(programId: Long) {
        val expoId = programs.findByIdOrNull(programId)?.expo?.id ?: missing()
        val pending =
            policies.stage(expoId, policies.command("DELETE_TRAINING", programId)) {
                val current = programs.findByIdOrNull(programId) ?: missing()
                ProgramChangePayload(listOf(ProgramMutation(policies.definition(current), null)))
            } ?: return
        applyChange.execute(expoId, pending.changeId)
    }

    private fun missing(): Nothing = throw ExpectedException(HttpStatus.NOT_FOUND, "연수 프로그램을 찾지 못 했습니다.")
}
