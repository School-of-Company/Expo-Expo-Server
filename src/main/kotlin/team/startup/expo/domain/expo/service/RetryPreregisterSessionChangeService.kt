package team.startup.expo.domain.expo.service

interface RetryPreregisterSessionChangeService {
    fun execute(
        expoId: String,
        sessionId: Long,
    )
}
