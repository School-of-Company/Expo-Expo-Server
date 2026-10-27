package team.startup.expo.domain.expo.service.impl

import org.springframework.data.repository.findByIdOrNull
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import team.startup.expo.domain.expo.presentation.dto.response.ProgramChangePayload
import team.startup.expo.domain.expo.presentation.dto.response.ProgramDefinition
import team.startup.expo.domain.expo.presentation.dto.response.ProgramType
import team.startup.expo.domain.expo.repository.AdminChangeHistoryRepository
import team.startup.expo.domain.expo.repository.ExpoRepository
import team.startup.expo.domain.expo.repository.ProgramChangeRepository
import team.startup.expo.domain.expo.service.ApplyProgramChangeService
import team.startup.expo.domain.expo.service.ExpoEventSupport
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
import team.startup.expo.global.attendance.ProgramAttendanceClient
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
    private val attendances: ProgramAttendanceClient,
    private val support: ProgramPolicySupport,
    private val client: ProgramPolicyClient,
    private val images: AttachExpoImageService,
    private val attachment: ImageAttachmentSupport,
    private val transactions: TransactionTemplate,
    private val history: AdminChangeHistoryRepository,
    private val events: ExpoEventSupport,
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
        val deleted = payload.mutations.filter { it.definition == null }.map { requireNotNull(it.previous) }
        if (!change.applied) applyLocally(expoId, changeId, payload, deleted)
        if (deleted.isEmpty()) return
        // 원격 삭제는 되돌릴 수 없으므로 로컬 삭제 커밋 뒤에 실행하고, 실패하면 변경을 남겨 재시도한다.
        deleted.forEach { previous ->
            when (previous.programType) {
                ProgramType.STANDARD -> standardDependencies.deleteApplications(requireNotNull(previous.id))
                ProgramType.TRAINING -> trainingDependencies.deleteApplications(requireNotNull(previous.id))
            }
        }
        transactions.executeWithoutResult {
            expos.findLockedById(expoId) ?: return@executeWithoutResult
            changes.findByIdOrNull(expoId)?.takeIf { it.changeId == changeId }?.let(changes::delete)
        }
    }

    private fun applyLocally(
        expoId: String,
        changeId: UUID,
        payload: ProgramChangePayload,
        deleted: List<ProgramDefinition>,
    ) {
        try {
            client.prepare(expoId, changeId, payload)
        } catch (exception: ExpectedException) {
            if (exception.status == HttpStatus.CONFLICT) {
                transactions.executeWithoutResult {
                    expos.findLockedById(expoId) ?: return@executeWithoutResult
                    changes.findByIdOrNull(expoId)?.takeIf { it.changeId == changeId && !it.applied }?.let {
                        payload.expoUpdate?.let { request -> attachment.release(request.coverImage, expoId) }
                        changes.delete(it)
                    }
                }
            }
            throw exception
        }
        try {
            // 설정 누락처럼 재시도로 풀리지 않는 실패는 로컬 삭제 전에 거부한다.
            if (deleted.isNotEmpty()) attendances.requireConfiguration()
            // #160: Form 조회는 박람회·폼 락 밖에서 끝낸다.
            val forms = support.checkForms(payload.mutations.mapNotNull { it.definition })
            transactions.executeWithoutResult {
                val expo = expos.findLockedById(expoId) ?: missing()
                if (expo.deletingAt != null) conflict()
                val current =
                    changes.findByIdOrNull(expoId)?.takeIf { it.changeId == changeId && !it.applied } ?: return@executeWithoutResult
                payload.mutations.mapNotNull { it.definition }.forEach { support.validateForApply(it, forms) }
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
                deleted.forEach { previous ->
                    history.record(
                        expoId,
                        "PROGRAM_DELETE",
                        previous.programType.name,
                        requireNotNull(previous.id),
                        previous.title,
                        current.adminId,
                    )
                    when (previous.programType) {
                        ProgramType.STANDARD -> standards.deleteById(requireNotNull(previous.id))
                        ProgramType.TRAINING -> trainings.deleteById(requireNotNull(previous.id))
                    }
                }
                if (deleted.isEmpty()) changes.delete(current) else changes.save(current.apply { applied = true })
                events.changed(expoId)
            }
        } catch (exception: Exception) {
            if (payload.mutations.none { it.previous != null && (it.previous.policy != null || it.definition?.policy != null) }) {
                transactions.executeWithoutResult {
                    expos.findLockedById(expoId) ?: return@executeWithoutResult
                    changes.findByIdOrNull(expoId)?.takeIf { it.changeId == changeId && !it.applied }?.let {
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
