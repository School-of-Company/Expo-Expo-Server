package team.startup.expo.domain.expo.messaging

import team.startup.expo.domain.expo.entity.Expo
import team.startup.expo.domain.expo.entity.PreregisterSession
import team.startup.expo.domain.expo.presentation.dto.response.ProgramChangePayload
import team.startup.expo.domain.expo.presentation.dto.response.ProgramType
import team.startup.expo.domain.standard.entity.StandardProgram
import team.startup.expo.domain.training.entity.Category
import team.startup.expo.domain.training.entity.TrainingProgram
import team.startup.expo.global.common.time.toProgramLocalDateTime
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.time.Instant
import java.util.UUID

// expo.expo.changed 메시지. 스키마와 소비 규칙은 docs/expo-change-events.md에 있다.
object ExpoChangedEvent {
    const val TOPIC = "expo.expo.changed"
    const val SCHEMA_VERSION = 1

    // 아웃박스 행의 불변 값으로만 만들므로 재시도해도 같은 메시지가 나간다.
    fun message(
        mapper: ObjectMapper,
        eventId: String,
        version: Long,
        occurredAt: Instant,
        expoId: String,
        snapshot: String?,
    ): String {
        val node =
            mapper
                .createObjectNode()
                .put("schemaVersion", SCHEMA_VERSION)
                .put("eventId", eventId)
                .put("version", version)
                .put("occurredAt", occurredAt.toString())
                .put("expoId", expoId)
                .put("deleted", snapshot == null)
        snapshot?.let { mapper.readTree(it).properties().forEach { (key, value) -> node.replace(key, value) } }
        return mapper.writeValueAsString(node)
    }
}

data class ExpoEventSnapshot(
    val expo: ExpoState,
    val standardPrograms: List<StandardProgramState>,
    val trainingPrograms: List<TrainingProgramState>,
    val preregisterSessions: List<PreregisterSessionState>,
) {
    companion object {
        fun of(
            expo: Expo,
            standards: List<StandardProgram>,
            trainings: List<TrainingProgram>,
            sessions: List<PreregisterSession>,
            mapper: ObjectMapper,
            programChange: ProgramChangePayload? = null,
        ): ExpoEventSnapshot {
            // 진행 중인 프로그램 변경(tb_program_change 행)이 있으면 Expo는 프로그램 조회를 409로 답한다(ProgramPolicySupport.ensureAvailable).
            // 프로그램별 pendingRevision은 관리자 목록 API(ProgramPolicySupport.pendingRevisions)와 같은 값이다.
            fun pending(type: ProgramType): Map<Long, Long> =
                programChange
                    ?.mutations
                    .orEmpty()
                    .mapNotNull { it.previous }
                    .filter { it.programType == type }
                    .associate { requireNotNull(it.id) to (it.revision + 1) }
            val pendingStandards = pending(ProgramType.STANDARD)
            val pendingTrainings = pending(ProgramType.TRAINING)
            return ExpoEventSnapshot(
                ExpoState(
                    title = expo.title,
                    description = expo.description,
                    startedDay = expo.startedDay,
                    finishedDay = expo.finishedDay,
                    location = expo.location,
                    coverImage = expo.coverImage,
                    x = expo.x,
                    y = expo.y,
                    preregisterTeacherCapacity = expo.preregisterTeacherCapacity,
                    preregisterNonTeacherCapacity = expo.preregisterNonTeacherCapacity,
                    participantPhoneVerificationRequired = expo.participantPhoneVerificationRequired,
                    participantPhoneVerificationRevision = expo.participantPhoneVerificationRevision,
                    deleting = expo.deletingAt != null,
                    programChangePending = programChange != null,
                ),
                standards.sortedBy { it.id }.map {
                    StandardProgramState(
                        id = requireNotNull(it.id),
                        title = it.title,
                        startedAt = programTime(it.startedAt),
                        endedAt = programTime(it.endedAt),
                        groupId = it.groupId,
                        policy = it.policyJson?.let(mapper::readTree),
                        place = it.place,
                        revision = it.revision,
                        pendingRevision = pendingStandards[it.id],
                    )
                },
                trainings.sortedBy { it.id }.map {
                    TrainingProgramState(
                        id = requireNotNull(it.id),
                        title = it.title,
                        startedAt = programTime(it.startedAt),
                        endedAt = programTime(it.endedAt),
                        category = it.category,
                        groupId = it.groupId,
                        policy = it.policyJson?.let(mapper::readTree),
                        place = it.place,
                        revision = it.revision,
                        pendingRevision = pendingTrainings[it.id],
                    )
                },
                sessions.sortedWith(compareBy<PreregisterSession>({ it.startedAt }, { it.id })).map {
                    PreregisterSessionState(
                        id = requireNotNull(it.id),
                        title = it.title,
                        startedAt = it.startedAt,
                        endedAt = it.endedAt,
                        place = it.place,
                        capacity = it.capacity,
                        waitingCapacity = it.waitingCapacity,
                        closed = it.closed,
                        revision = it.revision,
                        cancelEndedAt = it.cancelEndedAt,
                        teacherCapacity = it.teacherCapacity,
                        nonTeacherCapacity = it.nonTeacherCapacity,
                    )
                },
            )
        }

        // 예전 "yyyy-MM-dd HH:mm" 저장값도 "yyyy-MM-ddTHH:mm"(KST 지역 일시)로 맞춘다. 해석하지 못하면 저장값을 그대로 둔다.
        private fun programTime(stored: String): String = runCatching { stored.toProgramLocalDateTime().toString() }.getOrDefault(stored)
    }
}

data class ExpoState(
    val title: String,
    val description: String,
    val startedDay: String,
    val finishedDay: String,
    val location: String,
    val coverImage: String?,
    val x: String,
    val y: String,
    val preregisterTeacherCapacity: Int?,
    val preregisterNonTeacherCapacity: Int?,
    val participantPhoneVerificationRequired: Boolean,
    val participantPhoneVerificationRevision: Long,
    val deleting: Boolean,
    val programChangePending: Boolean,
)

data class StandardProgramState(
    val id: Long,
    val title: String,
    val startedAt: String,
    val endedAt: String,
    val groupId: UUID,
    val policy: JsonNode?,
    val place: String?,
    val revision: Long,
    val pendingRevision: Long?,
)

data class TrainingProgramState(
    val id: Long,
    val title: String,
    val startedAt: String,
    val endedAt: String,
    val category: Category,
    val groupId: UUID,
    val policy: JsonNode?,
    val place: String?,
    val revision: Long,
    val pendingRevision: Long?,
)

data class PreregisterSessionState(
    val id: Long,
    val title: String,
    val startedAt: Instant,
    val endedAt: Instant,
    val place: String,
    val capacity: Int,
    val waitingCapacity: Int,
    val closed: Boolean,
    val revision: Long,
    val cancelEndedAt: Instant?,
    val teacherCapacity: Int?,
    val nonTeacherCapacity: Int?,
)
