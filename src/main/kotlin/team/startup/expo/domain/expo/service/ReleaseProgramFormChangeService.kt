package team.startup.expo.domain.expo.service

import java.util.UUID

interface ReleaseProgramFormChangeService {
    fun execute(
        formId: UUID,
        changeId: UUID,
    )
}
