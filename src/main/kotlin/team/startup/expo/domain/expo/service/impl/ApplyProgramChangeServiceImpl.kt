package team.startup.expo.domain.expo.service.impl

import org.springframework.data.repository.findByIdOrNull
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import team.startup.expo.domain.expo.presentation.dto.response.ProgramType
import team.startup.expo.domain.expo.repository.AdminChangeHistoryRepository
import team.startup.expo.domain.expo.repository.ExpoRepository
import team.startup.expo.domain.expo.repository.ProgramChangeRepository
import team.startup.expo.domain.expo.service.ApplyProgramChangeService
import team.startup.expo.domain.expo.service.ProgramPolicyClient
import team.startup.expo.domain.expo.service.ProgramPolicySupport
import team.startup.expo.domain.image.service.AttachExpoImageService
import team.startup.expo.domain.image.service.ImageAttachmentSupport
import team.startup.expo.domain.standard.entity.StandardProgram
import team.startup.expo.domain.standard.repository.StandardProgramRepository
import team.startup.expo.domain.standard.service.StandardDependenciesClient
import team.startup.expo.domain.training.entity.TrainingProgram
import team.startup.expo.domain.training.repository.TrainingProgramRepository
import team.startup.expo.domain.training.service.TrainingDependenciesClient
import team.startup.expo.global.exception.ExpectedException
import java.util.UUID

@Service
class ApplyProgramChangeServiceImpl(
    private val expos: ExpoRepository,
    private val changes: ProgramChangeRepository,
    private val standards: StandardProgramRepository,
    private val trainings: TrainingProgramRepository,
    private val standardDependencies: StandardDependenciesClient,
    private val trainingDependencies: TrainingDependenciesClient,
    private val support: ProgramPolicySupport,
    private val client: ProgramPolicyClient,
    private val images: AttachExpoImageService,
    private val attachment: ImageAttachmentSupport,
    private val transactions: TransactionTemplate,
    private val history: AdminChangeHistoryRepository,
) : ApplyProgramChangeService {
    override fun execute(
        expoId: String,
        changeId: UUID,
    ) {
        val change =
            transactions.execute {
                val expo = expos.findLockedById(expoId) ?: missing()
                if (expo.deletingAt != null) conflict()
                changes.findByIdOrNull(expoId)?.takeIf { it.changeId == changeId }
            } ?: return
        val payload = support.payload(change)
        try {
            client.prepare(expoId, changeId, payload)
        } catch (exception: ExpectedException) {
            if (exception.status == HttpStatus.CONFLICT) {
                transactions.executeWithoutResult {
                    expos.findLockedById(expoId) ?: return@executeWithoutResult
                    changes.findByIdOrNull(expoId)?.takeIf { it.changeId == changeId }?.let {
                        payload.expoUpdate?.let { request -> attachment.release(request.coverImage, expoId) }
                        changes.delete(it)
                    }
                }
            }
            throw exception
        }
        try {
            transactions.executeWithoutResult {
                val expo = expos.findLockedById(expoId) ?: missing()
                if (expo.deletingAt != null) conflict()
                val current = changes.findByIdOrNull(expoId)?.takeIf { it.changeId == changeId } ?: return@executeWithoutResult
                payload.mutations.mapNotNull { it.definition }.forEach(support::validateForApply)
                payload.mutations.mapNotNull { it.previous }.forEach { previous ->
                    val actual =
                        when (previous.programType) {
                            ProgramType.STANDARD -> standards.findByIdOrNull(requireNotNull(previous.id))?.let(support::definition)
                            ProgramType.TRAINING -> trainings.findByIdOrNull(requireNotNull(previous.id))?.let(support::definition)
                        }
                    if (actual != previous) conflict()
                }
                payload.expoUpdate?.let { request ->
                    images.execute(request.coverImage, requireNotNull(payload.uploadedBy), expoId, previousUrl = expo.coverImage)
                    expos.updateInfo(
                        expoId,
                        request.title,
                        request.description,
                        request.startedDay.toString(),
                        request.finishedDay.toString(),
                        request.location,
                        request.coverImage,
                        request.x,
                        request.y,
                        request.preregisterTeacherCapacity,
                        request.preregisterNonTeacherCapacity,
                    )
                }
                val updatedExpo = expos.getReferenceById(expoId)
                payload.mutations.mapNotNull { it.definition }.forEach { definition ->
                    when (definition.programType) {
                        ProgramType.STANDARD -> {
                            val saved =
                                standards.save(
                                    StandardProgram(
                                        id = definition.id,
                                        title = definition.title,
                                        startedAt = definition.startedAt,
                                        endedAt = definition.endedAt,
                                        expo = updatedExpo,
                                        groupId = definition.groupId,
                                        policyJson = support.json(definition.policy),
                                        place = definition.place,
                                        revision = definition.revision,
                                    ),
                                )
                            history.record(
                                expoId,
                                if (definition.id == null) "PROGRAM_CREATE" else "PROGRAM_UPDATE",
                                definition.programType.name,
                                requireNotNull(saved.id),
                                definition.title,
                                current.adminId,
                            )
                        }

                        ProgramType.TRAINING -> {
                            val saved =
                                trainings.save(
                                    TrainingProgram(
                                        id = definition.id,
                                        title = definition.title,
                                        startedAt = definition.startedAt,
                                        endedAt = definition.endedAt,
                                        category = requireNotNull(definition.category),
                                        expo = updatedExpo,
                                        groupId = definition.groupId,
                                        policyJson = support.json(definition.policy),
                                        place = definition.place,
                                        revision = definition.revision,
                                    ),
                                )
                            history.record(
                                expoId,
                                if (definition.id == null) "PROGRAM_CREATE" else "PROGRAM_UPDATE",
                                definition.programType.name,
                                requireNotNull(saved.id),
                                definition.title,
                                current.adminId,
                            )
                        }
                    }
                }
                standards.flush()
                trainings.flush()
                payload.mutations.filter { it.definition == null }.forEach { mutation ->
                    val previous = requireNotNull(mutation.previous)
                    history.record(
                        expoId,
                        "PROGRAM_DELETE",
                        previous.programType.name,
                        requireNotNull(previous.id),
                        previous.title,
                        current.adminId,
                    )
                    when (previous.programType) {
                        ProgramType.STANDARD -> {
                            standardDependencies.deleteApplications(requireNotNull(previous.id))
                            standards.deleteById(previous.id)
                        }

                        ProgramType.TRAINING -> {
                            trainingDependencies.deleteApplications(requireNotNull(previous.id))
                            trainings.deleteById(previous.id)
                        }
                    }
                }
                changes.delete(current)
            }
        } catch (exception: Exception) {
            if (payload.mutations.none { it.previous != null && (it.previous.policy != null || it.definition?.policy != null) }) {
                transactions.executeWithoutResult {
                    expos.findLockedById(expoId) ?: return@executeWithoutResult
                    changes.findByIdOrNull(expoId)?.takeIf { it.changeId == changeId }?.let {
                        payload.expoUpdate?.let { request -> attachment.release(request.coverImage, expoId) }
                        changes.delete(it)
                    }
                }
            }
            throw exception
        }
    }

    private fun missing(): Nothing = throw ExpectedException(HttpStatus.NOT_FOUND, "박람회를 찾지 못 했습니다.")

    private fun conflict(): Nothing = throw ExpectedException(HttpStatus.CONFLICT, "박람회 또는 프로그램 상태가 변경됐습니다.")
}
