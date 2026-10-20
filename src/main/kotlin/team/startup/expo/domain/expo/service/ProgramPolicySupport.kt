package team.startup.expo.domain.expo.service

import org.springframework.data.repository.findByIdOrNull
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionTemplate
import team.startup.expo.domain.expo.entity.Expo
import team.startup.expo.domain.expo.entity.ProgramChange
import team.startup.expo.domain.expo.presentation.dto.request.NOTICE_PLACE_MAX_LENGTH
import team.startup.expo.domain.expo.presentation.dto.request.ProgramPolicyRequest
import team.startup.expo.domain.expo.presentation.dto.request.validateNoticeLabel
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
import team.startup.expo.global.common.time.toProgramLocalDateTime
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
    private val pendingForms = ThreadLocal<MutableList<ProgramDefinition>>()
    private val checkedForms = ThreadLocal<Map<Any, FormCheck>>()

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
        validateLabels(result)
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
                startedAt = storedIfSameTime(startedAt, current.startedAt),
                endedAt = storedIfSameTime(endedAt, current.endedAt),
                category = category,
                groupId = group,
                policy = policy?.copy(groupId = group) ?: current.policy,
            )
        if (revision != null && revision != current.revision) {
            if (revision == current.revision - 1 && desired == current) return null
            conflict("프로그램 버전이 변경됐습니다. 다시 조회해 주세요.")
        }
        if (desired == current) return null
        validateLabels(desired, current)
        validate(desired, if (group != current.groupId) group else null, desired.policy != current.policy)
        if (current.revision == Long.MAX_VALUE) conflict("프로그램 버전을 더 늘릴 수 없습니다.")
        return ProgramMutation(current, desired.copy(revision = current.revision + 1))
    }

    // #183: 예전 "yyyy-MM-dd HH:mm" 저장값과 같은 시각이면 저장값을 유지해 표기만 다른 수정을 변경으로 보지 않는다.
    private fun storedIfSameTime(
        requested: String,
        stored: String,
    ): String =
        if (runCatching {
                requested.toProgramLocalDateTime() == stored.toProgramLocalDateTime()
            }.getOrDefault(false)
        ) {
            stored
        } else {
            requested
        }

    fun stage(
        expoId: String,
        command: String,
        build: (Expo) -> ProgramChangePayload,
    ): ProgramChange? =
        transaction {
            val expo = expos.findLockedById(expoId) ?: missingExpo()
            if (expo.deletingAt != null) conflict("삭제 중인 박람회입니다.")
            changes.findByIdOrNull(expoId)?.let {
                if (it.commandJson != command) conflict("진행 중인 프로그램 변경을 먼저 재시도해 주세요.")
                return@transaction it
            }
            if (operations.hasUnresolvedApplications(expoId)) conflict("확인 중인 연수 신청이 있어 프로그램을 변경할 수 없습니다.")
            val payload = build(expo)
            if (payload.mutations.any { it.previous?.revision == Long.MAX_VALUE }) conflict("프로그램 버전을 더 늘릴 수 없습니다.")
            if (payload.mutations.isEmpty() && payload.expoUpdate == null) return@transaction null
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
                results.clear()
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

    // 저장된 값과 같은 제목·장소는 통과시켜 #172 이전에 저장된 프로그램도 그대로 수정할 수 있게 한다.
    private fun validateLabels(
        definition: ProgramDefinition,
        current: ProgramDefinition? = null,
    ) {
        if (definition.title != current?.title) validateNoticeLabel("프로그램 제목", definition.title)
        definition.place?.takeIf { it != current?.place }?.let { validateNoticeLabel("프로그램 장소", it, NOTICE_PLACE_MAX_LENGTH) }
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
        if (validateForm) checkForm(definition)
    }

    fun validateForApply(
        definition: ProgramDefinition,
        checks: Map<Any, FormCheck>,
    ) {
        definition.policy?.let {
            forms.lock()
            if (forms.blocked(it.formId)) conflict("프로그램 폼 변경이 진행 중이거나 복구가 필요합니다.")
            checkForm(definition, checks)
        }
    }

    // #160: Form 조회를 박람회·폼 락과 트랜잭션 밖에서 한다. 본문을 한 번 실행해 폼 검증이 필요한 정책을 모으고, 있으면 롤백한 뒤
    // 락 밖에서 검증하고 본문을 다시 실행한다. 다시 실행할 때는 원격 호출 없이 같은 정책을 검증했는지와 그사이 폼 변경이 없었는지만 대조한다.
    // 롤백해도 IDENTITY 값은 소모되므로 프로그램 행을 만드는 본문에는 creating을 쓴다.
    private fun <T> transaction(block: () -> T): T? {
        val pending = mutableListOf<ProgramDefinition>()
        pendingForms.set(pending)
        val result =
            try {
                transactions.execute { status -> block().also { if (pending.isNotEmpty()) status.setRollbackOnly() } }
            } finally {
                pendingForms.remove()
            }
        return if (pending.isEmpty()) result else checked(checkForms(pending), block)
    }

    // 생성은 요청 정책만으로 폼 검증이 정해지므로 본문을 미리 실행하지 않고 요청 정책을 먼저 검증한다.
    fun <T> creating(
        expoId: String,
        programs: List<Pair<ProgramType, ProgramPolicyRequest?>>,
        block: () -> T,
    ): T? =
        checked(
            checkForms(
                programs.mapNotNull { (type, policy) ->
                    policy?.let { ProgramDefinition(expoId, type, null, "", "", "", groupId = UUID(0, 0), policy = it, revision = 0) }
                },
            ),
            block,
        )

    private fun <T> checked(
        checks: Map<Any, FormCheck>,
        block: () -> T,
    ): T? {
        checkedForms.set(checks)
        try {
            return transactions.execute { block() }
        } finally {
            checkedForms.remove()
        }
    }

    // 트랜잭션 밖에서 호출한다. 폼 변경 행은 지우지 않으므로 조회 전 행 수가 락 안에서도 같으면 그사이 새 폼 변경이 없었다.
    fun checkForms(definitions: List<ProgramDefinition>): Map<Any, FormCheck> =
        definitions.filter { it.policy != null }.associateBy(::formKey).mapValues { (_, definition) ->
            val version = forms.version(requireNotNull(definition.policy).formId)
            val failure =
                try {
                    definition.policy.validate()
                    if (version != null) client.validateForm(definition)
                    null
                } catch (exception: ExpectedException) {
                    exception
                }
            FormCheck(version, failure)
        }

    private fun checkForm(
        definition: ProgramDefinition,
        checks: Map<Any, FormCheck>? = checkedForms.get(),
    ) {
        val policy = definition.policy ?: return
        pendingForms.get()?.let {
            it += definition
            return
        }
        val check = checks?.get(formKey(definition))
        if (check == null || check.version != forms.version(policy.formId)) conflict("확인하는 동안 프로그램 폼이 바뀌었습니다. 다시 시도해 주세요.")
        check.failure?.let { throw it }
    }

    // Form 검증 결과는 행사·유형·정책으로만 정해진다. 묶음 ID는 생성할 때마다 새로 정해지므로 뺀다.
    private fun formKey(definition: ProgramDefinition): Any =
        Triple(definition.expoId, definition.programType, definition.policy?.copy(groupId = null))

    data class FormCheck(
        val version: Long?,
        val failure: ExpectedException?,
    )

    private fun missingExpo(): Nothing = throw ExpectedException(HttpStatus.NOT_FOUND, "박람회를 찾지 못 했습니다.")

    private fun conflict(message: String): Nothing = throw ExpectedException(HttpStatus.CONFLICT, message)
}
