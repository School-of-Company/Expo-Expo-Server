package team.startup.expo.domain.expo.service

import java.util.UUID

interface ApplyProgramChangeService {
    fun execute(
        expoId: String,
        changeId: UUID,
    )
}
