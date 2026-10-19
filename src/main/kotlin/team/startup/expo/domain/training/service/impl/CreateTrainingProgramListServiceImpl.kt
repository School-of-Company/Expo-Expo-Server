package team.startup.expo.domain.training.service.impl

import jakarta.validation.Validator
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import team.startup.expo.domain.expo.presentation.dto.response.ProgramType
import team.startup.expo.domain.expo.repository.AdminChangeHistoryRepository
import team.startup.expo.domain.expo.repository.ExpoRepository
import team.startup.expo.domain.expo.service.ProgramPolicySupport
import team.startup.expo.domain.training.entity.TrainingProgram
import team.startup.expo.domain.training.presentation.dto.request.AddTrainingProgramRequest
import team.startup.expo.domain.training.repository.TrainingProgramRepository
import team.startup.expo.domain.training.service.CreateTrainingProgramListService
import team.startup.expo.global.exception.ExpectedException

@Service
class CreateTrainingProgramListServiceImpl(
    private val expos: ExpoRepository,
    private val policies: ProgramPolicySupport,
    private val programs: TrainingProgramRepository,
    private val validator: Validator,
    private val history: AdminChangeHistoryRepository,
) : CreateTrainingProgramListService {
    override fun execute(
        expoId: String,
        requests: List<AddTrainingProgramRequest>,
    ) {
        policies.creating(expoId, requests.map { ProgramType.TRAINING to it.policy }) {
            if (requests.any { validator.validate(it).isNotEmpty() }) {
                throw ExpectedException(HttpStatus.BAD_REQUEST, "잘못된 요청입니다.")
            }
            val expo = expos.findLockedById(expoId) ?: throw ExpectedException(HttpStatus.NOT_FOUND, "박람회를 찾지 못 했습니다.")
            if (expo.deletingAt != null) throw ExpectedException(HttpStatus.CONFLICT, "삭제 중인 박람회입니다.")
            programs
                .saveAll(
                    requests.map { request ->
                        val definition =
                            policies.create(
                                expoId,
                                ProgramType.TRAINING,
                                request.title,
                                request.startedAt.toString(),
                                request.endedAt.toString(),
                                request.category,
                                request.policy,
                                place = request.place,
                            )
                        TrainingProgram(
                            title = request.title,
                            startedAt = request.startedAt.toString(),
                            endedAt = request.endedAt.toString(),
                            category = request.category,
                            expo = expo,
                            groupId = definition.groupId,
                            policyJson = policies.json(definition.policy),
                            place = definition.place,
                        )
                    },
                ).forEach {
                    history.record(expoId, "PROGRAM_CREATE", "TRAINING", requireNotNull(it.id), it.title, history.currentAdminId())
                }
        }
    }
}
