package team.startup.expo.domain.training.service.impl

import org.springframework.data.repository.findByIdOrNull
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import team.startup.expo.domain.expo.presentation.dto.response.ProgramChangePayload
import team.startup.expo.domain.expo.service.ApplyProgramChangeService
import team.startup.expo.domain.expo.service.ProgramPolicySupport
import team.startup.expo.domain.training.presentation.dto.request.UpdateTrainingProgramRequest
import team.startup.expo.domain.training.repository.TrainingProgramRepository
import team.startup.expo.domain.training.service.UpdateTrainingProgramService
import team.startup.expo.global.exception.ExpectedException

@Service
class UpdateTrainingProgramServiceImpl(
    private val programs: TrainingProgramRepository,
    private val policies: ProgramPolicySupport,
    private val applyChange: ApplyProgramChangeService,
) : UpdateTrainingProgramService {
    override fun execute(
        trainingProgramId: Long,
        request: UpdateTrainingProgramRequest,
    ) {
        val expoId = programs.findByIdOrNull(trainingProgramId)?.expo?.id ?: missing()
        val pending =
            policies.stage(expoId, policies.command("UPDATE_TRAINING", trainingProgramId, request)) {
                val current = programs.findByIdOrNull(trainingProgramId) ?: missing()
                val mutation =
                    policies.update(
                        policies.definition(current),
                        request.title,
                        request.startedAt.toString(),
                        request.endedAt.toString(),
                        request.category,
                        request.policy,
                        request.revision,
                        place = request.place,
                    )
                ProgramChangePayload(listOfNotNull(mutation))
            } ?: return
        applyChange.execute(expoId, pending.changeId)
    }

    private fun missing(): Nothing = throw ExpectedException(HttpStatus.NOT_FOUND, "연수 프로그램을 찾지 못 했습니다.")
}
