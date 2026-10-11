package team.startup.expo.domain.expo.service

import java.util.UUID

interface ApplyPreregisterSessionChangeService {
    fun execute(
        expoId: String,
        sessionId: Long,
        changeId: UUID,
    )
}
