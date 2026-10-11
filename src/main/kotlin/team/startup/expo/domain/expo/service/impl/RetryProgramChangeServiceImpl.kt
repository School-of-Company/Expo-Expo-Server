package team.startup.expo.domain.expo.service.impl

import org.springframework.data.repository.findByIdOrNull
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import team.startup.expo.domain.expo.repository.ExpoRepository
import team.startup.expo.domain.expo.repository.ProgramChangeRepository
import team.startup.expo.domain.expo.service.ApplyProgramChangeService
import team.startup.expo.domain.expo.service.RetryProgramChangeService
import team.startup.expo.global.exception.ExpectedException

@Service
class RetryProgramChangeServiceImpl(
    private val expos: ExpoRepository,
    private val changes: ProgramChangeRepository,
    private val applyChange: ApplyProgramChangeService,
    private val transactions: TransactionTemplate,
) : RetryProgramChangeService {
    override fun execute(expoId: String) {
        val pending =
            transactions.execute {
                val expo = expos.findLockedById(expoId) ?: throw ExpectedException(HttpStatus.NOT_FOUND, "박람회를 찾지 못 했습니다.")
                if (expo.deletingAt != null) throw ExpectedException(HttpStatus.CONFLICT, "삭제 중인 박람회입니다.")
                changes.findByIdOrNull(expoId)
            } ?: return
        applyChange.execute(expoId, pending.changeId)
    }
}
