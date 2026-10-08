package team.startup.expo.domain.expo.service.impl

import org.springframework.data.repository.findByIdOrNull
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import team.startup.expo.domain.expo.repository.ExpoRepository
import team.startup.expo.domain.expo.repository.PreregisterSessionChangeRepository
import team.startup.expo.domain.expo.repository.PreregisterSessionRepository
import team.startup.expo.domain.expo.service.ApplyPreregisterSessionChangeService
import team.startup.expo.domain.expo.service.RetryPreregisterSessionChangeService
import team.startup.expo.global.exception.ExpectedException

@Service
class RetryPreregisterSessionChangeServiceImpl(
    private val expos: ExpoRepository,
    private val sessions: PreregisterSessionRepository,
    private val pendingChanges: PreregisterSessionChangeRepository,
    private val applyChange: ApplyPreregisterSessionChangeService,
    private val transactions: TransactionTemplate,
) : RetryPreregisterSessionChangeService {
    override fun execute(
        expoId: String,
        sessionId: Long,
    ) {
        val changeId =
            transactions.execute {
                val expo = expos.findLockedById(expoId) ?: throw ExpectedException(HttpStatus.NOT_FOUND, "박람회를 찾지 못 했습니다.")
                if (expo.deletingAt != null) throw ExpectedException(HttpStatus.CONFLICT, "삭제 중인 박람회입니다.")
                if (sessions.findByIdAndExpoId(sessionId, expoId) == null && sessions.existsById(sessionId)) {
                    throw ExpectedException(HttpStatus.NOT_FOUND, "회차를 찾지 못 했습니다.")
                }
                pendingChanges.findByIdOrNull(sessionId)?.changeId
            } ?: return
        applyChange.execute(expoId, sessionId, changeId)
    }
}
