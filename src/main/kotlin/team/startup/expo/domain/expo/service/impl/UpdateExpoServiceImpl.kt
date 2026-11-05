package team.startup.expo.domain.expo.service.impl

import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import team.startup.expo.domain.expo.presentation.dto.request.ProgramPolicyRequest
import team.startup.expo.domain.expo.presentation.dto.request.UpdateExpoRequest
import team.startup.expo.domain.expo.presentation.dto.response.ProgramChangePayload
import team.startup.expo.domain.expo.presentation.dto.response.ProgramDefinition
import team.startup.expo.domain.expo.presentation.dto.response.ProgramMutation
import team.startup.expo.domain.expo.presentation.dto.response.ProgramType
import team.startup.expo.domain.expo.service.AdminAccessSupport
import team.startup.expo.domain.expo.service.ApplyProgramChangeService
import team.startup.expo.domain.expo.service.ProgramPolicySupport
import team.startup.expo.domain.expo.service.UpdateExpoService
import team.startup.expo.domain.image.service.ImageAttachmentSupport
import team.startup.expo.domain.standard.repository.StandardProgramRepository
import team.startup.expo.domain.training.entity.Category
import team.startup.expo.domain.training.repository.TrainingProgramRepository
import team.startup.expo.global.exception.ExpectedException
import java.time.LocalDateTime

@Service
class UpdateExpoServiceImpl(
    private val admins: AdminAccessSupport,
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
        request.validatePeriod()
        request.validatePreregisterCapacities()
        val uploadedBy =
            admins.currentName()
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
                request.updateStandardProRequestDto.mapNotNullTo(mutations) { p ->
                    mutation(
                        expoId,
                        ProgramType.STANDARD,
                        p.id?.let { policies.definition(standard.getValue(it)) },
                        p.title,
                        p.startedAt,
                        p.endedAt,
                        null,
                        p.policy,
                        p.revision,
                        p.place,
                    )
                }
                request.updateTrainingProRequestDto.mapNotNullTo(mutations) { p ->
                    mutation(
                        expoId,
                        ProgramType.TRAINING,
                        p.id?.let { policies.definition(training.getValue(it)) },
                        p.title,
                        p.startedAt,
                        p.endedAt,
                        p.category,
                        p.policy,
                        p.revision,
                        p.place,
                    )
                }
                standard.filterKeys { it !in standardIds }.values.forEach { mutations += ProgramMutation(policies.definition(it), null) }
                training.filterKeys { it !in trainingIds }.values.forEach { mutations += ProgramMutation(policies.definition(it), null) }
                ProgramChangePayload(mutations, request, uploadedBy)
            } ?: return
        applyChange.execute(expoId, pending.changeId)
    }

    private fun mutation(
        expoId: String,
        type: ProgramType,
        current: ProgramDefinition?,
        title: String,
        startedAt: LocalDateTime,
        endedAt: LocalDateTime,
        category: Category?,
        policy: ProgramPolicyRequest?,
        revision: Long?,
        place: String?,
    ): ProgramMutation? =
        if (current == null) {
            ProgramMutation(null, policies.create(expoId, type, title, startedAt.toString(), endedAt.toString(), category, policy, place))
        } else {
            policies.update(current, title, startedAt.toString(), endedAt.toString(), category, policy, revision, place)
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
