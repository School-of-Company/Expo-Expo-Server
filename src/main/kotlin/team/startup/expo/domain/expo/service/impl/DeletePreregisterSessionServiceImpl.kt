package team.startup.expo.domain.expo.service.impl

import org.springframework.data.repository.findByIdOrNull
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import team.startup.expo.domain.expo.entity.PreregisterSessionChange
import team.startup.expo.domain.expo.entity.PreregisterSessionChangeOperation
import team.startup.expo.domain.expo.repository.ExpoRepository
import team.startup.expo.domain.expo.repository.PreregisterSessionChangeRepository
import team.startup.expo.domain.expo.repository.PreregisterSessionRepository
import team.startup.expo.domain.expo.service.ApplyPreregisterSessionChangeService
import team.startup.expo.domain.expo.service.DeletePreregisterSessionService
import team.startup.expo.global.exception.ExpectedException

@Service
class DeletePreregisterSessionServiceImpl(
    private val expos: ExpoRepository,
    private val sessions: PreregisterSessionRepository,
    private val pendingChanges: PreregisterSessionChangeRepository,
    private val applyChange: ApplyPreregisterSessionChangeService,
    private val transactions: TransactionTemplate,
) : DeletePreregisterSessionService {
    override fun execute(
        expoId: String,
        sessionId: Long,
        revision: Long,
    ) {
        if (revision <= 0 || revision == Long.MAX_VALUE || sessionId <= 0) {
            throw ExpectedException(HttpStatus.BAD_REQUEST, "회차 버전 또는 ID가 올바르지 않습니다.")
        }
        val pending =
            transactions.execute {
                val expo = expos.findLockedById(expoId) ?: throw ExpectedException(HttpStatus.NOT_FOUND, "박람회를 찾지 못 했습니다.")
                if (expo.deletingAt != null) throw ExpectedException(HttpStatus.CONFLICT, "삭제 중인 박람회입니다.")
                val session = sessions.findByIdAndExpoId(sessionId, expoId)
                if (session == null) {
                    if (sessions.existsById(sessionId)) throw ExpectedException(HttpStatus.NOT_FOUND, "회차를 찾지 못 했습니다.")
                    return@execute null
                }
                if (session.revision != revision) throw ExpectedException(HttpStatus.CONFLICT, "회차 버전이 변경됐습니다. 다시 조회해 주세요.")
                val existing = pendingChanges.findByIdOrNull(sessionId)
                if (existing != null) {
                    if (existing.operation != PreregisterSessionChangeOperation.DELETE) {
                        throw ExpectedException(HttpStatus.CONFLICT, "진행 중인 회차 변경을 먼저 재시도해 주세요.")
                    }
                    return@execute existing
                }
                pendingChanges.save(PreregisterSessionChange(sessionId, revision + 1, PreregisterSessionChangeOperation.DELETE, true, null))
            } ?: return
        applyChange.execute(expoId, sessionId, pending.changeId)
    }
}
