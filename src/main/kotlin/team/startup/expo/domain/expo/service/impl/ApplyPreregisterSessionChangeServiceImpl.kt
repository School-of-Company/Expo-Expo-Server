package team.startup.expo.domain.expo.service.impl

import org.springframework.data.repository.findByIdOrNull
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import team.startup.expo.domain.expo.entity.PreregisterSession
import team.startup.expo.domain.expo.entity.PreregisterSessionChangeOperation
import team.startup.expo.domain.expo.presentation.dto.response.PreregisterSessionResponse
import team.startup.expo.domain.expo.repository.ExpoRepository
import team.startup.expo.domain.expo.repository.PreregisterSessionChangeRepository
import team.startup.expo.domain.expo.repository.PreregisterSessionRepository
import team.startup.expo.domain.expo.service.ApplyPreregisterSessionChangeService
import team.startup.expo.domain.expo.service.PreregisterSessionChangesClient
import team.startup.expo.global.exception.ExpectedException
import tools.jackson.databind.ObjectMapper
import java.util.UUID

@Service
class ApplyPreregisterSessionChangeServiceImpl(
    private val expos: ExpoRepository,
    private val sessions: PreregisterSessionRepository,
    private val pendingChanges: PreregisterSessionChangeRepository,
    private val changes: PreregisterSessionChangesClient,
    private val transactions: TransactionTemplate,
    private val mapper: ObjectMapper,
) : ApplyPreregisterSessionChangeService {
    override fun execute(
        expoId: String,
        sessionId: Long,
        changeId: UUID,
    ) {
        val pending =
            transactions.execute {
                val expo = expos.findLockedById(expoId) ?: throw ExpectedException(HttpStatus.NOT_FOUND, "박람회를 찾지 못 했습니다.")
                if (expo.deletingAt != null) throw ExpectedException(HttpStatus.CONFLICT, "삭제 중인 박람회입니다.")
                if (sessions.findByIdAndExpoId(sessionId, expoId) == null) return@execute null
                pendingChanges.findByIdOrNull(sessionId)?.takeIf { it.changeId == changeId }
            } ?: return
        val definition = pending.definitionJson?.let { mapper.readValue(it, PreregisterSessionResponse::class.java) }
        try {
            changes.prepare(expoId, sessionId, pending.nextRevision, pending.operation.name, pending.definitionChanged, definition)
        } catch (exception: ExpectedException) {
            if (exception.status == HttpStatus.CONFLICT) {
                transactions.executeWithoutResult {
                    expos.findLockedById(expoId) ?: return@executeWithoutResult
                    pendingChanges.findByIdOrNull(sessionId)?.takeIf { it.changeId == changeId }?.let { pendingChanges.delete(it) }
                }
            }
            throw exception
        }
        transactions.executeWithoutResult {
            val expo = expos.findLockedById(expoId) ?: throw ExpectedException(HttpStatus.NOT_FOUND, "박람회를 찾지 못 했습니다.")
            if (expo.deletingAt != null) throw ExpectedException(HttpStatus.CONFLICT, "삭제 중인 박람회입니다.")
            val currentPending = pendingChanges.findByIdOrNull(sessionId) ?: return@executeWithoutResult
            if (currentPending.changeId != changeId) return@executeWithoutResult
            val current = sessions.findByIdAndExpoId(sessionId, expoId) ?: throw ExpectedException(HttpStatus.NOT_FOUND, "회차를 찾지 못 했습니다.")
            if (current.revision != pending.nextRevision - 1) throw ExpectedException(HttpStatus.CONFLICT, "회차 버전이 변경됐습니다. 다시 조회해 주세요.")
            pendingChanges.delete(currentPending)
            pendingChanges.flush()
            when (pending.operation) {
                PreregisterSessionChangeOperation.UPDATE -> {
                    val updated = requireNotNull(definition)
                    sessions.save(
                        PreregisterSession(
                            id = sessionId,
                            expo = expo,
                            title = updated.title,
                            startedAt = updated.startedAt,
                            endedAt = updated.endedAt,
                            place = updated.place,
                            capacity = updated.capacity,
                            waitingCapacity = updated.waitingCapacity,
                            closed = updated.closed,
                            revision = updated.revision,
                        ),
                    )
                }

                PreregisterSessionChangeOperation.DELETE -> {
                    sessions.delete(current)
                }
            }
        }
    }
}
