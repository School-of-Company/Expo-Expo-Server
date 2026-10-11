package team.startup.expo.domain.expo.service.impl

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import team.startup.expo.domain.expo.repository.ProgramFormChangeRepository
import team.startup.expo.domain.expo.service.ReleaseProgramFormChangeService
import java.util.UUID

@Service
class ReleaseProgramFormChangeServiceImpl(
    private val forms: ProgramFormChangeRepository,
) : ReleaseProgramFormChangeService {
    @Transactional
    override fun execute(
        formId: UUID,
        changeId: UUID,
    ) {
        forms.lock()
        forms.release(formId, changeId)
    }
}
