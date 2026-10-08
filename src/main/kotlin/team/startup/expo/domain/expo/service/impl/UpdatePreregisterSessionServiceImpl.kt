package team.startup.expo.domain.expo.service.impl

import org.springframework.data.repository.findByIdOrNull
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import team.startup.expo.domain.expo.entity.PreregisterSessionChange
import team.startup.expo.domain.expo.entity.PreregisterSessionChangeOperation
import team.startup.expo.domain.expo.presentation.dto.request.UpdatePreregisterSessionRequest
import team.startup.expo.domain.expo.presentation.dto.response.toResponse
import team.startup.expo.domain.expo.repository.ExpoRepository
import team.startup.expo.domain.expo.repository.PreregisterSessionChangeRepository
import team.startup.expo.domain.expo.repository.PreregisterSessionRepository
import team.startup.expo.domain.expo.service.ApplyPreregisterSessionChangeService
import team.startup.expo.domain.expo.service.UpdatePreregisterSessionService
import team.startup.expo.global.exception.ExpectedException
import tools.jackson.databind.ObjectMapper
import java.time.temporal.ChronoUnit

@Service
class UpdatePreregisterSessionServiceImpl(
    private val expos: ExpoRepository,
    private val sessions: PreregisterSessionRepository,
    private val pendingChanges: PreregisterSessionChangeRepository,
    private val applyChange: ApplyPreregisterSessionChangeService,
    private val transactions: TransactionTemplate,
    private val mapper: ObjectMapper,
) : UpdatePreregisterSessionService {
    override fun execute(
        expoId: String,
        sessionId: Long,
        request: UpdatePreregisterSessionRequest,
    ) {
        val definition = request.definition
        definition.validatePeriod()
        val pending =
            transactions.execute {
                val expo = expos.findLockedById(expoId) ?: throw ExpectedException(HttpStatus.NOT_FOUND, "박람회를 찾지 못 했습니다.")
                if (expo.deletingAt != null) throw ExpectedException(HttpStatus.CONFLICT, "삭제 중인 박람회입니다.")
                val current =
                    sessions.findByIdAndExpoId(sessionId, expoId) ?: throw ExpectedException(HttpStatus.NOT_FOUND, "회차를 찾지 못 했습니다.")
                val original = current.toResponse()
                val desired =
                    original.copy(
                        title = definition.title,
                        startedAt = definition.startedAt.truncatedTo(ChronoUnit.MICROS),
                        endedAt = definition.endedAt.truncatedTo(ChronoUnit.MICROS),
                        place = definition.place,
                        capacity = definition.capacity,
                        waitingCapacity = definition.waitingCapacity,
                        closed = definition.closed,
                    )
                if (current.revision != request.revision) {
                    if (current.revision > 1 && request.revision == current.revision - 1 && original == desired) return@execute null
                    throw ExpectedException(HttpStatus.CONFLICT, "회차 버전이 변경됐습니다. 다시 조회해 주세요.")
                }
                val existing = pendingChanges.findByIdOrNull(sessionId)
                if (existing == null && original == desired) return@execute null
                if (current.revision == Long.MAX_VALUE) throw ExpectedException(HttpStatus.CONFLICT, "회차 버전을 더 늘릴 수 없습니다.")
                val updated = desired.copy(revision = current.revision + 1)
                val definitionJson = mapper.writeValueAsString(updated)
                if (existing != null) {
                    if (existing.operation != PreregisterSessionChangeOperation.UPDATE || existing.definitionJson != definitionJson) {
                        throw ExpectedException(HttpStatus.CONFLICT, "진행 중인 회차 변경을 먼저 재시도해 주세요.")
                    }
                    return@execute existing
                }
                pendingChanges.save(
                    PreregisterSessionChange(
                        sessionId,
                        updated.revision,
                        PreregisterSessionChangeOperation.UPDATE,
                        original.copy(closed = desired.closed) != desired,
                        definitionJson,
                    ),
                )
            } ?: return
        applyChange.execute(expoId, sessionId, pending.changeId)
    }
}
