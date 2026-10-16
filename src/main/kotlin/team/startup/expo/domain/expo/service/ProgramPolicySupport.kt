package team.startup.expo.domain.expo.service

import org.springframework.data.repository.findByIdOrNull
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionTemplate
import team.startup.expo.domain.expo.entity.Expo
import team.startup.expo.domain.expo.entity.ProgramChange
import team.startup.expo.domain.expo.presentation.dto.request.ProgramPolicyRequest
import team.startup.expo.domain.expo.presentation.dto.response.ProgramChangePayload
import team.startup.expo.domain.expo.presentation.dto.response.ProgramDefinition
import team.startup.expo.domain.expo.presentation.dto.response.ProgramMutation
import team.startup.expo.domain.expo.presentation.dto.response.ProgramPolicyBulkResult
import team.startup.expo.domain.expo.presentation.dto.response.ProgramPolicyBulkStatus
import team.startup.expo.domain.expo.presentation.dto.response.ProgramType
import team.startup.expo.domain.expo.repository.AdminChangeHistoryRepository
import team.startup.expo.domain.expo.repository.ExpoRepository
import team.startup.expo.domain.expo.repository.ProgramChangeRepository
import team.startup.expo.domain.expo.repository.ProgramFormChangeRepository
import team.startup.expo.domain.standard.entity.StandardProgram
import team.startup.expo.domain.standard.repository.StandardProgramRepository
import team.startup.expo.domain.training.entity.Category
import team.startup.expo.domain.training.entity.TrainingProgram
import team.startup.expo.domain.training.repository.TrainingProgramRepository
import team.startup.expo.domain.training.repository.TrainingSmsOutboxRepository
import team.startup.expo.global.exception.ExpectedException
import tools.jackson.databind.ObjectMapper
import java.time.LocalDateTime
import java.util.UUID

@Component
class ProgramPolicySupport(
    private val expos: ExpoRepository,
    private val operations: TrainingSmsOutboxRepository,
    private val changes: ProgramChangeRepository,
    private val standards: StandardProgramRepository,
    private val trainings: TrainingProgramRepository,
    private val client: ProgramPolicyClient,
    private val transactions: TransactionTemplate,
    private val mapper: ObjectMapper,
    private val forms: ProgramFormChangeRepository,
    private val history: AdminChangeHistoryRepository,
) {
    fun command(vararg values: Any?): String = mapper.writeValueAsString(values)

    fun policy(json: String?): ProgramPolicyRequest? = json?.let { mapper.readValue(it, ProgramPolicyRequest::class.java) }

    fun json(policy: ProgramPolicyRequest?): String? = policy?.let { mapper.writeValueAsString(it) }

    fun definition(program: StandardProgram): ProgramDefinition =
        ProgramDefinition(
            requireNotNull(program.expo).id,
            ProgramType.STANDARD,
            program.id,
            program.title,
            program.startedAt,
            program.endedAt,
            groupId = program.groupId,
            policy = policy(program.policyJson),
            revision = program.revision,
            place = program.place,
        )

    fun definition(program: TrainingProgram): ProgramDefinition =
        ProgramDefinition(
            requireNotNull(program.expo).id,
            ProgramType.TRAINING,
            program.id,
            program.title,
            program.startedAt,
            program.endedAt,
            program.category,
            program.groupId,
            policy(program.policyJson),
            program.revision,
            place = program.place,
        )

    fun ensureAvailable(expoId: String) {
        if (expos.existsByIdAndDeletingAtIsNotNull(expoId)) conflict("삭제 중인 박람회입니다.")
        if (changes.existsById(expoId)) conflict("진행 중인 프로그램 변경을 먼저 재시도해 주세요.")
    }

    fun ensureAdmission(
        expoId: String,
        policyJson: String?,
    ) {
        ensureAvailable(expoId)
        if (policyJson != null) conflict("정책이 설정된 프로그램은 참가자와 폼 답변을 포함하는 신청 API를 사용해 주세요.")
    }

    fun pendingRevisions(
        expoId: String,
        type: ProgramType,
    ): Map<Long, Long> {
        val pending = changes.findByIdOrNull(expoId) ?: return emptyMap()
        return payload(pending)
            .mutations
            .mapNotNull { it.previous }
            .filter { it.programType == type }
            .associate { requireNotNull(it.id) to (it.revision + 1) }
    }

    fun payload(change: ProgramChange): ProgramChangePayload = mapper.readValue(change.payloadJson, ProgramChangePayload::class.java)

    fun create(
        expoId: String,
        type: ProgramType,
        title: String,
        startedAt: String,
        endedAt: String,
        category: Category?,
        policy: ProgramPolicyRequest?,
        place: String? = null,
    ): ProgramDefinition {
        ensureAvailable(expoId)
        val group = policy?.groupId ?: UUID.randomUUID()
        val result =
            ProgramDefinition(
                expoId,
                type,
                null,
                title,
                startedAt,
                endedAt,
                category,
                group,
                policy?.copy(
                    groupId = group,
                ),
                1,
                place?.trim()?.takeIf {
                    it.isNotEmpty()
                },
            )
        validate(result, policy?.groupId)
        return result
    }

    fun update(
        current: ProgramDefinition,
        title: String,
        startedAt: String,
        endedAt: String,
        category: Category?,
        policy: ProgramPolicyRequest?,
        revision: Long?,
        place: String? = null,
    ): ProgramMutation? {
        if ((current.policy != null || policy != null) && revision == null) {
            throw ExpectedException(HttpStatus.BAD_REQUEST, "프로그램 정책 변경에는 revision이 필요합니다.")
        }
        val group = policy?.groupId ?: current.groupId
        val desired =
            current.copy(
                title = title,
                place = if (place == null) current.place else place.trim().takeIf { it.isNotEmpty() },
                startedAt = startedAt,
                endedAt = endedAt,
                category = category,
                groupId = group,
                policy = policy?.copy(groupId = group) ?: current.policy,
            )
        if (revision != null && revision != current.revision) {
            if (revision == current.revision - 1 && desired == current) return null
            conflict("프로그램 버전이 변경됐습니다. 다시 조회해 주세요.")
        }
        if (desired == current) return null
        validate(desired, if (group != current.groupId) group else null, desired.policy != current.policy)
        if (current.revision == Long.MAX_VALUE) conflict("프로그램 버전을 더 늘릴 수 없습니다.")
        return ProgramMutation(current, desired.copy(revision = current.revision + 1))
    }

    fun stage(
        expoId: String,
        command: String,
        build: (Expo) -> ProgramChangePayload,
    ): ProgramChange? =
        transactions.execute {
            val expo = expos.findLockedById(expoId) ?: missingExpo()
            if (expo.deletingAt != null) conflict("삭제 중인 박람회입니다.")
            changes.findByIdOrNull(expoId)?.let {
                if (it.commandJson != command) conflict("진행 중인 프로그램 변경을 먼저 재시도해 주세요.")
                return@execute it
            }
            if (operations.hasUnresolvedApplications(expoId)) conflict("확인 중인 연수 신청이 있어 프로그램을 변경할 수 없습니다.")
            val payload = build(expo)
            if (payload.mutations.any { it.previous?.revision == Long.MAX_VALUE }) conflict("프로그램 버전을 더 늘릴 수 없습니다.")
            if (payload.mutations.isEmpty() && payload.expoUpdate == null) return@execute null
            changes.saveAndFlush(
                ProgramChange(
                    expoId,
                    commandJson = command,
                    payloadJson = mapper.writeValueAsString(payload),
                    adminId = history.currentAdminId(),
                ),
            )
        }

    // 프로그램마다 따로 검증하고 통과한 변경만 하나의 ProgramChange로 묶는다. 같은 명령의 재시도는 결과 목록이 비어 있다.
    fun stageBulk(
        expoId: String,
        command: String,
        change: (ProgramPolicyRequest) -> ProgramPolicyRequest,
    ): Pair<ProgramChange?, List<ProgramPolicyBulkResult>> {
        val results = mutableListOf<ProgramPolicyBulkResult>()
        val pending =
            stage(expoId, command) {
                val currents =
                    standards.findByExpoIdOrderByIdAsc(expoId).map(::definition) +
                        trainings.findByExpoIdOrderByIdAsc(expoId).map(::definition)
                val mutations =
                    currents.mapNotNull { current ->
                        val policy = current.policy
                        val result = { status: ProgramPolicyBulkStatus, reason: String? ->
                            results +=
                                ProgramPolicyBulkResult(current.programType, requireNotNull(current.id), current.title, status, reason)
                        }
                        if (policy == null) {
                            result(ProgramPolicyBulkStatus.SKIPPED_NO_POLICY, null)
                            return@mapNotNull null
                        }
                        try {
                            update(
                                current,
                                current.title,
                                current.startedAt,
                                current.endedAt,
                                current.category,
                                change(policy),
                                current.revision,
                            ).also {
                                result(
                                    if (it ==
                                        null
                                    ) {
                                        ProgramPolicyBulkStatus.UNCHANGED
                                    } else {
                                        ProgramPolicyBulkStatus.UPDATED
                                    },
                                    null,
                                )
                            }
                        } catch (exception: ExpectedException) {
                            if (exception.status.is5xxServerError) throw exception
                            result(ProgramPolicyBulkStatus.FAILED, exception.message)
                            null
                        }
                    }
                ProgramChangePayload(mutations)
            }
        return pending to results
    }

    private fun validate(
        definition: ProgramDefinition,
        linkedGroup: UUID?,
        validateForm: Boolean = true,
    ) {
        val start = LocalDateTime.parse(definition.startedAt.replace(' ', 'T'))
        val end = LocalDateTime.parse(definition.endedAt.replace(' ', 'T'))
        if (!start.isBefore(end)) throw ExpectedException(HttpStatus.BAD_REQUEST, "프로그램 시작은 종료보다 빨라야 합니다.")
        definition.policy?.validate()
        if (linkedGroup != null) {
            val owners =
                standards.findByGroupId(linkedGroup).map { requireNotNull(it.expo).id to ProgramType.STANDARD } +
                    trainings.findByGroupId(linkedGroup).map { requireNotNull(it.expo).id to ProgramType.TRAINING }
            if (owners.isEmpty()) throw ExpectedException(HttpStatus.NOT_FOUND, "프로그램 묶음을 찾지 못 했습니다.")
            if (owners.any { it.first != definition.expoId || it.second != definition.programType }) {
                throw ExpectedException(HttpStatus.BAD_REQUEST, "다른 행사 또는 유형의 프로그램 묶음입니다.")
            }
        }
        definition.policy?.let {
            forms.lock()
            if (forms.blocked(it.formId)) conflict("프로그램 폼 변경이 진행 중이거나 복구가 필요합니다.")
        }
        if (validateForm) client.validateForm(definition)
    }

    fun validateForApply(definition: ProgramDefinition) {
        definition.policy?.let {
            forms.lock()
            if (forms.blocked(it.formId)) conflict("프로그램 폼 변경이 진행 중이거나 복구가 필요합니다.")
            client.validateForm(definition)
        }
    }

    private fun missingExpo(): Nothing = throw ExpectedException(HttpStatus.NOT_FOUND, "박람회를 찾지 못 했습니다.")

    private fun conflict(message: String): Nothing = throw ExpectedException(HttpStatus.CONFLICT, message)
}
