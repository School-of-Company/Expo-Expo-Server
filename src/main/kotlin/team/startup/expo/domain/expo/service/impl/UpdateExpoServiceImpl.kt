package team.startup.expo.domain.expo.service.impl

import org.springframework.http.HttpStatus
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Service
import team.startup.expo.domain.expo.presentation.dto.request.UpdateExpoRequest
import team.startup.expo.domain.expo.presentation.dto.response.ProgramChangePayload
import team.startup.expo.domain.expo.presentation.dto.response.ProgramMutation
import team.startup.expo.domain.expo.presentation.dto.response.ProgramType
import team.startup.expo.domain.expo.service.ApplyProgramChangeService
import team.startup.expo.domain.expo.service.ProgramPolicySupport
import team.startup.expo.domain.expo.service.UpdateExpoService
import team.startup.expo.domain.image.service.ImageAttachmentSupport
import team.startup.expo.domain.standard.repository.StandardProgramRepository
import team.startup.expo.domain.training.repository.TrainingProgramRepository
import team.startup.expo.global.exception.ExpectedException

@Service
class UpdateExpoServiceImpl(
    private val standards: StandardProgramRepository,
    private val trainings: TrainingProgramRepository,
    private val policies: ProgramPolicySupport,
    private val images: ImageAttachmentSupport,
    private val applyChange: ApplyProgramChangeService,
) : UpdateExpoService {
    override fun execute(
        expoId: String,
        request: UpdateExpoRequest,
    ) {
        val uploadedBy =
            SecurityContextHolder.getContext().authentication?.name
                ?: throw ExpectedException(HttpStatus.UNAUTHORIZED, "인증이 필요합니다.")
        val pending =
            policies.stage(expoId, policies.command("UPDATE_EXPO", request, uploadedBy)) { expo ->
                if (request.coverImage != expo.coverImage) {
                    images.validate(request.coverImage, uploadedBy, expoId).reserve(expoId)
                }
                val standard = standards.findByExpo(expo).associateBy { requireNotNull(it.id) }
                val training = trainings.findByExpo(expo).associateBy { requireNotNull(it.id) }
                val standardIds = request.updateStandardProRequestDto.mapNotNull { it.id }
                val trainingIds = request.updateTrainingProRequestDto.mapNotNull { it.id }
                validateIds(standardIds, standard.keys)
                validateIds(trainingIds, training.keys)
                val mutations = mutableListOf<ProgramMutation>()
                request.updateStandardProRequestDto.forEach { p ->
                    if (p.id == null) {
                        mutations +=
                            ProgramMutation(
                                null,
                                policies.create(
                                    expoId,
                                    ProgramType.STANDARD,
                                    p.title,
                                    p.startedAt.toString(),
                                    p.endedAt.toString(),
                                    null,
                                    p.policy,
                                ),
                            )
                    } else {
                        policies
                            .update(
                                policies.definition(standard.getValue(p.id)),
                                p.title,
                                p.startedAt.toString(),
                                p.endedAt.toString(),
                                null,
                                p.policy,
                                p.revision,
                            )?.let(mutations::add)
                    }
                }
                request.updateTrainingProRequestDto.forEach { p ->
                    if (p.id == null) {
                        mutations +=
                            ProgramMutation(
                                null,
                                policies.create(
                                    expoId,
                                    ProgramType.TRAINING,
                                    p.title,
                                    p.startedAt.toString(),
                                    p.endedAt.toString(),
                                    p.category,
                                    p.policy,
                                ),
                            )
                    } else {
                        policies
                            .update(
                                policies.definition(training.getValue(p.id)),
                                p.title,
                                p.startedAt.toString(),
                                p.endedAt.toString(),
                                p.category,
                                p.policy,
                                p.revision,
                            )?.let(mutations::add)
                    }
                }
                standard.filterKeys { it !in standardIds }.values.forEach { mutations += ProgramMutation(policies.definition(it), null) }
                training.filterKeys { it !in trainingIds }.values.forEach { mutations += ProgramMutation(policies.definition(it), null) }
                ProgramChangePayload(mutations, request, uploadedBy)
            } ?: return
        applyChange.execute(expoId, pending.changeId)
    }

    private fun validateIds(
        requested: List<Long>,
        existing: Set<Long>,
    ) {
        if (requested.size != requested.distinct().size || requested.any { it !in existing }) {
            throw ExpectedException(HttpStatus.CONFLICT, "박람회 프로그램 정보가 충돌합니다.")
        }
    }
}
